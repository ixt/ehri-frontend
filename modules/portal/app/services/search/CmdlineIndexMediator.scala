package services.search

import javax.inject.Inject

import akka.actor.ActorRef
import com.google.common.collect.EvictingQueue
import defines.EntityType
import services.data.Constants

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process._


case class CmdlineIndexMediator @Inject()(implicit config: play.api.Configuration, executionContext: ExecutionContext)
extends SearchIndexMediator {
  def handle = CmdlineIndexMediatorHandle()
}

/**
  * Indexer which uses the command-line tool in
  * bin to index items.
  */
case class CmdlineIndexMediatorHandle(
  chan: Option[ActorRef] = None,
  processFunc: String => String = identity[String],
  progressFilter: Int => Boolean = _ % 100 == 0
)(implicit config: play.api.Configuration, executionContext: ExecutionContext)
  extends SearchIndexMediatorHandle {

  override def withChannel(actorRef: ActorRef, formatter: String => String, filter: Int => Boolean): CmdlineIndexMediatorHandle =
    copy(chan = Some(actorRef), processFunc = formatter, progressFilter = filter)

  /**
    * Process logger which buffers output to `bufferCount` lines
    */
  object logger extends ProcessLogger {
    // number of lines to buffer...
    var count = 0
    val errBuffer: EvictingQueue[String] = EvictingQueue.create[String](10)

    def buffer[T](f: => T): T = f

    def out(s: => String): Unit = report()

    def err(s: => String) {
      errBuffer.add(s)
      // This is a hack. All progress goes to stdout but we only
      // want to buffer that which contains the format:
      // [type] -> [id]
      if (s.contains("->")) report()
      else chan.foreach(_ ! processFunc(s))
    }

    import scala.collection.JavaConverters._
    def lastMessages: List[String] = errBuffer.asScala.toList

    private def report(): Unit = {
      count += 1
      if (progressFilter(count)) {
        chan.foreach(_ ! processFunc("Items processed: " + count))
      }
    }
  }

  private val binary = Seq("java", "-jar", jar)

  private def jar = config.getOptional[String]("solr.indexer.jar")
    .getOrElse(sys.error("No indexer jar configured for solr.indexer.jar"))

  private val restUrl = utils.serviceBaseUrl("ehridata", config)

  private val solrUrl = utils.serviceBaseUrl("solr", config)

  private val clearArgs = binary ++ Seq(
    "--solr", solrUrl
  )

  private val idxArgs = binary ++ Seq(
    "--index",
    "--solr", solrUrl,
    "--rest", restUrl,
    "-H", Constants.AUTH_HEADER_NAME + "=admin",
    "-H", Constants.STREAM_HEADER_NAME + "=true",
    "--verbose" // print one line of output per item
  )

  private def runProcess(cmd: Seq[String]) = Future {
    play.api.Logger.logger.debug("Index: {}", cmd.mkString(" "))
    val process: Process = cmd.run(logger)
    if (process.exitValue() != 0) {
      throw IndexingError("Exit code was " + process.exitValue() + "\nLast output: \n"
        + (if (logger.errBuffer.remainingCapacity > 0) "" else "... (truncated)\n")
        + logger.lastMessages.mkString("\n"))
    }
  }

  def indexIds(ids: String*): Future[Unit] = runProcess((idxArgs ++ ids.map(id => s"@$id")) :+ "--pretty")

  def indexTypes(entityTypes: Seq[EntityType.Value]): Future[Unit]
  = runProcess(idxArgs ++ entityTypes.map(_.toString))

  def indexChildren(entityType: EntityType.Value, id: String): Future[Unit]
  = runProcess(idxArgs ++ Seq(s"$entityType|$id"))

  def clearAll(): Future[Unit] = runProcess(clearArgs ++ Seq("--clear-all"))

  def clearTypes(entityTypes: Seq[EntityType.Value]): Future[Unit]
  = runProcess(clearArgs ++ entityTypes.flatMap(s => Seq("--clear-type", s.toString)))

  def clearIds(ids: String*): Future[Unit] = runProcess(clearArgs ++ ids.flatMap(id => Seq("--clear-id", id)))

  def clearKeyValue(key: String, value: String): Future[Unit]
  = runProcess(clearArgs ++ Seq("--clear-key-value", s"$key=$value"))
}
