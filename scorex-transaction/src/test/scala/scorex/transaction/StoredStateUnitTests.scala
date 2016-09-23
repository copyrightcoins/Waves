package scorex.transaction

import java.io.File

import org.h2.mvstore.MVStore
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import scorex.account.Account
import scorex.transaction.state.database.blockchain.StoredState
import scorex.transaction.state.database.state._

class StoredStateUnitTests extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers
with PrivateMethodTester with OptionValues with TransactionGen {

  val folder = "/tmp/scorex/test/"
  new File(folder).mkdirs()
  val stateFile = folder + "state.dat"
  new File(stateFile).delete()

  val db = new MVStore.Builder().fileName(stateFile).compress().open()
  val state = new StoredState(db)
  val testAdd = "aPFwzRp5TXCzi6DSuHmpmbQunopXRuxLk"
  val testAcc = new Account(testAdd)
  val testAssetAcc = AssetAcc(testAcc, None)
  val applyMethod = PrivateMethod[Unit]('applyChanges)

  property("private methods") {

    forAll(paymentGenerator, Gen.posNum[Long]) { (tx: PaymentTransaction,
                                                  balance: Long) =>
      state.balance(testAcc) shouldBe 0
      state.assetBalance(testAssetAcc) shouldBe 0
      state invokePrivate applyMethod(Map(testAssetAcc ->(AccState(balance), Seq(FeesStateChange(balance), tx, tx))))
      state.balance(testAcc) shouldBe balance
      state.assetBalance(testAssetAcc) shouldBe balance
      state.included(tx).value shouldBe state.stateHeight
      state invokePrivate applyMethod(Map(testAssetAcc ->(AccState(0L), Seq(tx))))
    }
  }

  property("Reopen state") {
    val balance = 1234L
    state invokePrivate applyMethod(Map(testAssetAcc ->(AccState(balance), Seq(FeesStateChange(balance)))))
    state.balance(testAcc) shouldBe balance
    db.close()

    val state2 = new StoredState(new MVStore.Builder().fileName(stateFile).compress().open())
    state2.balance(testAcc) shouldBe balance
    state2 invokePrivate applyMethod(Map(testAssetAcc ->(AccState(0L), Seq())))
  }

}
