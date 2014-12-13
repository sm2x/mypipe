package mypipe.producer.stdout

import mypipe.api._
import mypipe.api.event.{ DeleteMutation, Mutation, UpdateMutation, InsertMutation }
import mypipe.api.producer.Producer
import org.slf4j.LoggerFactory
import com.typesafe.config.Config

class StdoutProducer(config: Config) extends Producer(config) {

  protected val mutations = scala.collection.mutable.ListBuffer[String]()
  protected val log = LoggerFactory.getLogger(getClass)

  override def flush(): Boolean = {
    if (mutations.size > 0) {
      log.info("\n" + mutations.mkString("\n"))
      mutations.clear()
    }

    true
  }

  override def queueList(mutationz: List[Mutation[_]]): Boolean = {
    mutationz.foreach(queue)
    true
  }

  override def queue(mutation: Mutation[_]): Boolean = {
    mutation match {

      case i: InsertMutation ⇒ {
        mutations += s"INSERT INTO ${i.table.db}.${i.table.name} (${i.table.columns.map(_.name).mkString(", ")}) VALUES (${i.rows.head.columns.values.map(_.value).mkString(", ")})"
      }

      case u: UpdateMutation ⇒ {
        u.rows.foreach(rr ⇒ {

          val old = rr._1
          val cur = rr._2
          val pKeyColNames = if (u.table.primaryKey.isDefined) u.table.primaryKey.get.columns.map(_.name) else List.empty[String]

          val p = pKeyColNames.map(colName ⇒ {
            val cols = old.columns
            cols.filter(_._1.equals(colName))
            cols.head
          })

          val pKeyVals = p.map(_._2.value.toString)
          val where = Some(pKeyColNames.zip(pKeyVals).map(kv ⇒ kv._1 + "=" + kv._2).mkString(", ")).map(w ⇒ s"WHERE ($w)")
          val curValues = cur.columns.values.map(_.value)
          val colNames = u.table.columns.map(_.name)
          val updates = colNames.zip(curValues).map(kv ⇒ kv._1 + "=" + kv._2).mkString(", ")
          mutations += s"UPDATE ${u.table.db}.${u.table.name} SET ($updates) $where"
        })
      }

      case d: DeleteMutation ⇒ {
        d.rows.foreach(row ⇒ {

          val pKeyColNames = if (d.table.primaryKey.isDefined) d.table.primaryKey.get.columns.map(_.name) else List.empty[String]

          val p = pKeyColNames.map(colName ⇒ {
            val cols = row.columns
            cols.filter(_._1.equals(colName))
            cols.head
          })

          val pKeyVals = p.map(_._2.value.toString)
          val where = pKeyColNames.zip(pKeyVals).map(kv ⇒ kv._1 + "=" + kv._2).mkString(", ")
          mutations += s"DELETE FROM ${d.table.db}.${d.table.name} WHERE ($where)"

        })
      }

      case _ ⇒ {
      }

    }

    true
  }

  override def toString(): String = {
    "StdoutProducer"
  }

}
