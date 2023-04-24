/*
* Copyright (C) Mellanox Technologies Ltd. 2019. ALL RIGHTS RESERVED.
* See file LICENSE for terms.
*/
package org.apache.spark.shuffle.ucx

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.util.Random
import org.openucx.jucx.ucp._
import org.openucx.jucx.ucs.UcsConstants
import org.openucx.jucx.ucs.UcsConstants.MEMORY_TYPE
import org.openucx.jucx.{UcxCallback, UcxException, UcxUtils}
import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.ucx.memory.UcxBounceBufferMemoryBlock
import org.apache.spark.shuffle.ucx.utils.SerializationUtils
import org.apache.spark.shuffle.utils.UnsafeUtils
import org.apache.spark.unsafe.Platform
import org.apache.spark.util.ThreadUtils

import java.nio.ByteBuffer
import scala.collection.parallel.ForkJoinTaskSupport


class UcxFailureOperationResult(errorMsg: String) extends OperationResult {
  override def getStatus: OperationStatus.Value = OperationStatus.FAILURE

  override def getError: TransportError = new TransportError(errorMsg)

  override def getStats: Option[OperationStats] = None

  override def getData: MemoryBlock = null
}

class UcxAmDataMemoryBlock(ucpAmData: UcpAmData, offset: Long, size: Long,
                           refCount: AtomicInteger)
  extends MemoryBlock(ucpAmData.getDataAddress + offset, size, true) with Logging {

  override def close(): Unit = {
    if (refCount.decrementAndGet() == 0) {
      ucpAmData.close()
    }
  }
}

class UcxRefCountMemoryBlock(baseBlock: MemoryBlock, offset: Long, size: Long,
                             refCount: AtomicInteger)
  extends MemoryBlock(baseBlock.address + offset, size, true) with Logging {

  override def close(): Unit = {
    if (refCount.decrementAndGet() == 0) {
      baseBlock.close()
    }
  }
}

/**
 * Worker per thread wrapper, that maintains connection and progress logic.
 */
case class UcxWorkerWrapper(worker: UcpWorker, transport: UcxShuffleTransport, isClientWorker: Boolean,
                            id: Long = 0L)
  extends Closeable with Logging {

  private final val connections =  new TrieMap[transport.ExecutorId, UcpEndpoint]
  private val requestData = new TrieMap[Int, (Seq[OperationCallback], UcxRequest, transport.BufferAllocator)]
  private val tag = new AtomicInteger(Random.nextInt())
  private val flushRequests = new ConcurrentLinkedQueue[UcpRequest]()

  private val ioThreadPool = ThreadUtils.newForkJoinPool("IO threads",
    transport.ucxShuffleConf.numIoThreads)
  private val ioTaskSupport = new ForkJoinTaskSupport(ioThreadPool)

  if (isClientWorker) {
    // Receive block data handler
    worker.setAmRecvHandler(1,
      (headerAddress: Long, headerSize: Long, ucpAmData: UcpAmData, _: UcpEndpoint) => {
        val headerBuffer = UnsafeUtils.getByteBufferView(headerAddress, headerSize.toInt)
        val i = headerBuffer.getInt
        val data = requestData.remove(i)

        if (data.isEmpty) {
          throw new UcxException(s"No data for tag $i.")
        }

        val (callbacks, request, allocator) = data.get
        val stats = request.getStats.get.asInstanceOf[UcxStats]
        stats.receiveSize = ucpAmData.getLength

        // Header contains tag followed by sizes of blocks
        val numBlocks = (headerSize.toInt - UnsafeUtils.INT_SIZE) / UnsafeUtils.INT_SIZE

        var offset = 0
        val refCounts = new AtomicInteger(numBlocks)
        if (ucpAmData.isDataValid) {
          request.completed = true
          stats.endTime = System.nanoTime()
          logDebug(s"Received amData: $ucpAmData for tag $i " +
            s"in ${stats.getElapsedTimeNs} ns")

          for (b <- 0 until numBlocks) {
            val blockSize = headerBuffer.getInt
            if (callbacks(b) != null) {
              callbacks(b).onComplete(new OperationResult {
                override def getStatus: OperationStatus.Value = OperationStatus.SUCCESS

                override def getError: TransportError = null

                override def getStats: Option[OperationStats] = Some(stats)

                override def getData: MemoryBlock = new UcxAmDataMemoryBlock(ucpAmData, offset, blockSize, refCounts)
              })
              offset += blockSize
            }
          }
          if (callbacks.isEmpty) UcsConstants.STATUS.UCS_OK else UcsConstants.STATUS.UCS_INPROGRESS
        } else {
          val mem = allocator(ucpAmData.getLength)
          stats.amHandleTime = System.nanoTime()
          request.setRequest(worker.recvAmDataNonBlocking(ucpAmData.getDataHandle, mem.address, ucpAmData.getLength,
            new UcxCallback() {
              override def onSuccess(r: UcpRequest): Unit = {
                request.completed = true
                stats.endTime = System.nanoTime()
                logDebug(s"Received rndv data of size: ${mem.size} for tag $i in " +
                  s"${stats.getElapsedTimeNs} ns " +
                  s"time from amHandle: ${System.nanoTime() - stats.amHandleTime} ns")
                for (b <- 0 until numBlocks) {
                  val blockSize = headerBuffer.getInt
                  callbacks(b).onComplete(new OperationResult {
                    override def getStatus: OperationStatus.Value = OperationStatus.SUCCESS

                    override def getError: TransportError = null

                    override def getStats: Option[OperationStats] = Some(stats)

                    override def getData: MemoryBlock = new UcxRefCountMemoryBlock(mem, offset, blockSize, refCounts)
                  })
                  offset += blockSize
                }

              }
            }, UcsConstants.MEMORY_TYPE.UCS_MEMORY_TYPE_HOST))
          UcsConstants.STATUS.UCS_OK
        }
      }, UcpConstants.UCP_AM_FLAG_PERSISTENT_DATA | UcpConstants.UCP_AM_FLAG_WHOLE_MSG)
  }

  override def close(): Unit = {
    val closeRequests = connections.map {
      case (_, endpoint) => endpoint.closeNonBlockingForce()
    }
    while (!closeRequests.forall(_.isCompleted)) {
      progress()
    }
    ioThreadPool.shutdown()
    connections.clear()
    worker.close()
  }

  /**
   * Blocking progress until there's outstanding flush requests.
   */
  def progressConnect(): Unit = {
    while (!flushRequests.isEmpty) {
      progress()
      flushRequests.removeIf(_.isCompleted)
    }
    logTrace(s"Flush completed. Number of connections: ${connections.keys.size}")
  }

  /**
   * The only place for worker progress
   */
  def progress(): Int = worker.synchronized {
    worker.progress()
  }

  /**
   * Establish connections to known instances.
   */
  def preconnect(): Unit = {
    transport.executorAddresses.keys.foreach(getConnection)
    progressConnect()
  }

  def connectByWorkerAddress(executorId: transport.ExecutorId, workerAddress: ByteBuffer): Unit = {
    logDebug(s"Worker $this connecting back to $executorId by worker address")
    val ep = worker.newEndpoint(new UcpEndpointParams().setName(s"Server connection to $executorId")
      .setUcpAddress(workerAddress))
    connections.put(executorId, ep)
  }

  def getConnection(executorId: transport.ExecutorId): UcpEndpoint = {

    val startTime = System.currentTimeMillis()
    while (!transport.executorAddresses.contains(executorId)) {
      if  (System.currentTimeMillis() - startTime >
        transport.ucxShuffleConf.getSparkConf.getTimeAsMs("spark.network.timeout", "100")) {
        throw new UcxException(s"Don't get a worker address for $executorId")
      }
    }

    connections.getOrElseUpdate(executorId,  {
      val address = transport.executorAddresses(executorId)
      val endpointParams = new UcpEndpointParams().setPeerErrorHandlingMode()
        .setSocketAddress(SerializationUtils.deserializeInetAddress(address)).sendClientId()
        .setErrorHandler(new UcpEndpointErrorHandler() {
          override def onError(ep: UcpEndpoint, status: Int, errorMsg: String): Unit = {
            logError(s"Endpoint to $executorId got an error: $errorMsg")
            connections.remove(executorId)
          }
        }).setName(s"Endpoint to $executorId")

      logDebug(s"Worker $this connecting to Executor($executorId, " +
        s"${SerializationUtils.deserializeInetAddress(address)}")
      val ep = worker.newEndpoint(endpointParams)
      val header = Platform.allocateDirectBuffer(UnsafeUtils.LONG_SIZE)
      header.putLong(id)
      header.rewind()
      val workerAddress = worker.getAddress

      ep.sendAmNonBlocking(1, UcxUtils.getAddress(header), UnsafeUtils.LONG_SIZE,
        UcxUtils.getAddress(workerAddress), workerAddress.capacity().toLong, UcpConstants.UCP_AM_SEND_FLAG_EAGER,
        new UcxCallback() {
          override def onSuccess(request: UcpRequest): Unit = {
            header.clear()
            workerAddress.clear()
          }
        }, MEMORY_TYPE.UCS_MEMORY_TYPE_HOST)
      flushRequests.add(ep.flushNonBlocking(null))
      ep
    })
  }

  def fetchBlocksByBlockIds(executorId: transport.ExecutorId, blockIds: Seq[BlockId],
                            resultBufferAllocator: transport.BufferAllocator,
                            callbacks: Seq[OperationCallback]): Seq[Request] = {
    val startTime = System.nanoTime()
    val headerSize = UnsafeUtils.INT_SIZE + UnsafeUtils.LONG_SIZE
    val ep = getConnection(executorId)

    val t = tag.incrementAndGet()

    val buffer = Platform.allocateDirectBuffer(headerSize + blockIds.map(_.serializedSize).sum)
    buffer.putInt(t)
    buffer.putLong(id)
    blockIds.foreach(b => b.serialize(buffer))

    val request = new UcxRequest(null, new UcxStats())
    requestData.put(t, (callbacks, request, resultBufferAllocator))

    buffer.rewind()
    val address = UnsafeUtils.getAdress(buffer)
    val dataAddress = address + headerSize

    ep.sendAmNonBlocking(0, address,
      headerSize, dataAddress, buffer.capacity() - headerSize,
      UcpConstants.UCP_AM_SEND_FLAG_EAGER, new UcxCallback() {
       override def onSuccess(request: UcpRequest): Unit = {
         buffer.clear()
         logDebug(s"Sent message on $ep to $executorId to fetch ${blockIds.length} blocks on tag $t id $id" +
           s"in ${System.nanoTime() - startTime} ns")
       }
     }, MEMORY_TYPE.UCS_MEMORY_TYPE_HOST)

    worker.progressRequest(ep.flushNonBlocking(null))
    Seq(request)
  }

  def handleFetchBlockRequest(blocks: Seq[Block], replyTag: Int, replyExecutor: Long): Unit = try {
    val tagAndSizes = UnsafeUtils.INT_SIZE + UnsafeUtils.INT_SIZE * blocks.length
    val resultMemory = transport.hostBounceBufferMemoryPool.get(tagAndSizes + blocks.map(_.getSize).sum)
      .asInstanceOf[UcxBounceBufferMemoryBlock]
    val resultBuffer = UcxUtils.getByteBufferView(resultMemory.address,
      resultMemory.size)
    resultBuffer.putInt(replyTag)

    var offset = 0
    val localBuffers = blocks.zipWithIndex.map {
      case (block, i) =>
        resultBuffer.putInt(UnsafeUtils.INT_SIZE + i * UnsafeUtils.INT_SIZE, block.getSize.toInt)
        resultBuffer.position(tagAndSizes + offset)
        val localBuffer = resultBuffer.slice()
        offset += block.getSize.toInt
        localBuffer.limit(block.getSize.toInt)
        localBuffer
    }
    // Do parallel read of blocks
    val blocksCollection = if (transport.ucxShuffleConf.numIoThreads > 1) {
      val parCollection = blocks.indices.par
      parCollection.tasksupport = ioTaskSupport
      parCollection
    } else {
      blocks.indices
    }

    for (i <- blocksCollection) {
      blocks(i).getBlock(localBuffers(i))
    }

    val startTime = System.nanoTime()
    val req = getConnection(replyExecutor).sendAmNonBlocking(1, resultMemory.address, tagAndSizes,
      resultMemory.address + tagAndSizes, resultMemory.size - tagAndSizes, 0, new UcxCallback {
        override def onSuccess(request: UcpRequest): Unit = {
          logTrace(s"Sent ${blocks.length} blocks of size: ${resultMemory.size} " +
            s"to tag $replyTag in ${System.nanoTime() - startTime} ns.")
          transport.hostBounceBufferMemoryPool.put(resultMemory)
        }

        override def onError(ucsStatus: Int, errorMsg: String): Unit = {
          logError(s"Failed to send $errorMsg")
        }
      }, new UcpRequestParams().setMemoryType(UcsConstants.MEMORY_TYPE.UCS_MEMORY_TYPE_HOST)
        .setMemoryHandle(resultMemory.memory))

    while (!req.isCompleted) {
      progress()
    }
  } catch {
    case ex: Throwable => logError(s"Failed to read and send data: $ex")
  }

}
