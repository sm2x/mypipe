package mypipe.snapshotter

import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import mypipe.api.consumer.{BinaryLogConsumer, ConfigLoader}
import mypipe.api.data._
import mypipe.api.event._
import mypipe.mysql._
import mypipe.util.Actors

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap

sealed trait SnapshotterEvent
case class SelectEvent(database: String, table: String, rows: Seq[Seq[Any]]) extends SnapshotterEvent
case class ShowMasterStatusEvent(filePosition: BinaryLogFilePosition) extends SnapshotterEvent

class SelectConsumer(override val config: Config)
    extends BinaryLogConsumer[SelectEvent]
    with ConfigBasedErrorHandlingBehaviour[SelectEvent]
    with ConfigBasedEventSkippingBehaviour
    with CacheableTableMapBehaviour
    with ConfigLoader
    with ConfigBasedConnectionSource {

  private val system = Actors.actorSystem
  private val dbMetadata = system.actorOf(MySQLMetadataManager.props(hostname, port, username, Some(password)), s"SelectConsumer-DBMetadataActor-$hostname:$port")
  private implicit val ec = system.dispatcher
  private implicit val timeout = Timeout(2.second)
  private val tables = scala.collection.mutable.HashMap[String, Table]()
  private var binlogPos: Option[BinaryLogFilePosition] = None

  def handleEvents(events: Seq[SnapshotterEvent]) = {
    events.foreach {
      case select: SelectEvent ⇒
        decodeEvent(select).foreach(s ⇒ listeners.foreach(_.onMutation(this, s.asInstanceOf[Mutation])))
      case ShowMasterStatusEvent(pos) ⇒
        binlogPos = Some(pos)
        log.info(s"Binary log position to resume from after snapshot: $binlogPos")
      case x ⇒
        log.warn(s"Ignoring unknown event: $x")
    }
  }

  /** Given a third-party BinLogEvent, this method decodes it to an
   *  mypipe specific Event type if it recognizes it.
   *  @param event the event to decode
   *  @return the decoded Event or None
   */
  override protected def decodeEvent(event: SelectEvent): Option[Event] = {
    val rowData = event.rows.map(_.map(_.asInstanceOf[java.io.Serializable]).toArray).toList.asJava
    getTable(event.database, event.table) match {
      case Some(table) ⇒
        val rows = createRows(table, rowData)
        Some(InsertMutation(0L, table, rows))
      case None ⇒
        log.error(s"Could not find table for event, skipping: $event")
        None
    }
  }

  protected def createRows(table: Table, evRows: java.util.List[Array[java.io.Serializable]]): List[Row] = {
    evRows.asScala.map(evRow ⇒ {

      // zip the names and values from the table's columns and the row's data and
      // create a map that contains column names to Column objects with values
      val cols = table.columns.zip(evRow).map(c ⇒ c._1.name → Column(c._1, c._2))
      val columns = ListMap.empty[String, Column] ++ cols.toArray

      Row(table, columns)

    }).toList
  }

  /** Gets the consumer's current position in the binary log.
   *  @return current BinLogPos
   */
  override def getBinaryLogPosition: Option[BinaryLogFilePosition] = binlogPos

  /** Gets this consumer's unique ID.
   *  @return Unique ID as a string.
   */
  override def id: String = s"select-consumer-$hostname-$port"

  override protected def onStop(): Unit = Unit
  override protected def onStart(): Future[Boolean] = Future.successful(true)

  override def toString = id

  private def getTable(database: String, table: String): Option[Table] = {
    tables.get(s"$database.$table") match {
      case table @ Some(_) ⇒ table
      case None ⇒
        val future = ask(dbMetadata, GetColumns(database, table)).asInstanceOf[Future[(List[ColumnMetadata], Option[PrimaryKey])]]
        try {
          val columns = Await.result(future, 2.seconds)
          val t = Table(0L, table, database, columns._1, columns._2)
          tables.put(s"$database.$table", t)
          Some(t)
        } catch {
          case e: Exception ⇒
            log.error(s"Exception caught while fetching table information for $database.$table")
            None
        }
    }
  }
}
