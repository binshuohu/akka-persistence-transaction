package sample.persistence

import akka.actor.ActorSystem
import sample.persistence.account.AccountActor
import sample.persistence.transaction.{TransactionManagerActor, TransferMoney}

object Main extends App {
  val system = ActorSystem("transaction")
  val clark = system.actorOf(AccountActor.props(active = true, balance = 500), "Clark")
  val bruce = system.actorOf(AccountActor.props(active = true, balance = 10000), "Bruce")

  val tm = system.actorOf(TransactionManagerActor.props(), "tm")

  clark ! "print"
  bruce ! "print"

  tm ! TransferMoney(123456L, "Clark", "Bruce", 300L)

  Thread.sleep(5000)
  clark ! "print"
  bruce ! "print"

}
