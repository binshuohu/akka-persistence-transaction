package sample.persistence

import akka.actor.ActorSystem
import sample.persistence.account.AccountActor
import sample.persistence.transaction.{TransactionManagerActor, TransferMoney}

//demonstrate compensation in case of insufficient balance
object Main2 extends App {
  val system = ActorSystem("transaction")
  val diana = system.actorOf(AccountActor.props(active = true, balance = 500), "Diana")
  val arthur = system.actorOf(AccountActor.props(active = true, balance = 10000), "Arthur")

  val tm = system.actorOf(TransactionManagerActor.props(), "tm2")

  diana ! "print"
  arthur ! "print"

  tm ! TransferMoney(123456L, "Diana", "Arthur", 1000L)

  Thread.sleep(5000)
  diana ! "print"
  arthur ! "print"

}
