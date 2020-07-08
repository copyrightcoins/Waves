package com.wavesplatform.it.sync

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.{Block, Transaction, TransactionInfo}
import com.wavesplatform.it.sync.transactions.OverflowBlock
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction.Transfer
import com.wavesplatform.transaction.{CreateAliasTransaction, TxVersion}
import org.asynchttpclient.Response
import org.scalatest
import org.scalatest.Assertion
import play.api.libs.json.{JsValue, Json}

class AmountAsStringSuite extends BaseTransactionSuite with OverflowBlock {

  val (headerName, headerValue) = ("Accept", "application/json;large-significand-format=string")

  test("amount as string in assets api") {
    val assetId = sender.issue(firstAddress, "assetName", "description", someAssetAmount, 8, fee = issueFee, waitForTx = true).id
    sender.issue(firstAddress, "assetName", "description", quantity = 1, decimals = 0, reissuable = false, fee = issueFee, waitForTx = true).id
    val currentHeight = sender.height
    sender.assetsDetails(assetId, amountsAsStrings = true).quantity shouldBe someAssetAmount
    sender.assetBalance(firstAddress, assetId, amountsAsStrings = true).balance shouldBe someAssetAmount
    sender.assetsBalance(firstAddress, amountsAsStrings = true).balances.head.balance shouldBe someAssetAmount
    sender.nftList(firstAddress, 1, amountsAsStrings = true).head.quantity shouldBe 1

    sender.waitForHeight(currentHeight + 1)
    val assetDistribution = sender.getWithCustomHeader(
      s"/assets/$assetId/distribution/$currentHeight/limit/1",
      headerValue = "application/json;large-significand-format=string"
    )
    (parseResponse(assetDistribution) \ "items" \ firstAddress).as[String] shouldBe s"$someAssetAmount"
  }

  test("amount as string in addresses api") {
    val firstBalance = sender.balanceDetails(firstAddress)
    sender.balance(firstAddress, amountsAsStrings = true).balance shouldBe firstBalance.regular
    sender.balance(firstAddress, confirmations = Some(1), amountsAsStrings = true).balance shouldBe firstBalance.regular

    val balanceDetails = sender.balanceDetails(firstAddress, amountsAsStrings = true)
    balanceDetails.regular shouldBe firstBalance.regular
    balanceDetails.generating shouldBe firstBalance.generating
    balanceDetails.available shouldBe firstBalance.available
    balanceDetails.effective shouldBe firstBalance.effective

    sender.effectiveBalance(firstAddress, amountsAsStrings = true).balance shouldBe firstBalance.effective
    sender.effectiveBalance(firstAddress, confirmations = Some(1), amountsAsStrings = true).balance shouldBe firstBalance.effective
  }

  test("amount as string in exchange transaction") {
    val exchanger      = KeyPair("exchanger".getBytes)
    val transferTxId   = sender.transfer(firstAddress, exchanger.toAddress.toString, transferAmount, minFee, waitForTx = true).id
    val transferTxInfo = sender.transactionInfo[TransactionInfo](transferTxId, amountsAsStrings = true)
    transferTxInfo.amount shouldBe Some(transferAmount)
    transferTxInfo.fee shouldBe minFee

    val amount = 1000000
    val price  = 1000
    def checkExchangeTx(exchangeTx: Transaction): scalatest.Assertion = {
      exchangeTx.amount shouldBe Some(amount)
      exchangeTx.price shouldBe Some(price)
      exchangeTx.sellMatcherFee shouldBe Some(matcherFee)
      exchangeTx.buyMatcherFee shouldBe Some(matcherFee)
      exchangeTx.sellOrderMatcherFee shouldBe Some(matcherFee)
      exchangeTx.buyOrderMatcherFee shouldBe Some(matcherFee)
      exchangeTx.fee shouldBe matcherFee
    }
    val exchAssetId = sender
      .broadcastIssue(exchanger, "exchange asset", "", someAssetAmount, 8, fee = issueFee, reissuable = true, script = None, waitForTx = true)
      .id
    val ts = System.currentTimeMillis()
    val buyOrder = Order.buy(
      version = TxVersion.V2,
      exchanger,
      exchanger.publicKey,
      AssetPair.createAssetPair("WAVES", exchAssetId).get,
      amount,
      price,
      ts,
      ts + Order.MaxLiveTime,
      matcherFee
    )
    val sellOrder = Order.sell(
      version = TxVersion.V2,
      exchanger,
      exchanger.publicKey,
      AssetPair.createAssetPair("WAVES", exchAssetId).get,
      amount,
      price,
      ts,
      ts + Order.MaxLiveTime,
      matcherFee
    )

    overflowBlock()
    val exchangeTx =
      sender.broadcastExchange(exchanger, buyOrder, sellOrder, amount, price, matcherFee, matcherFee, matcherFee, amountsAsStrings = true)
    checkExchangeTx(exchangeTx)

    val utxExchangeTxInfoById = sender.utxById(exchangeTx.id, amountsAsStrings = true)
    val utxExchangeTxInfo     = sender.utx(amountsAsStrings = true)
    checkExchangeTx(utxExchangeTxInfoById)
    checkExchangeTx(utxExchangeTxInfo.head)

    val exchangeTxHeight           = sender.waitForTransaction(exchangeTx.id).height
    val exchangeTxBlockLast        = sender.lastBlock(amountsAsStrings = true).transactions.head
    val exchangeTxBlockAt          = sender.blockAt(exchangeTxHeight, amountsAsStrings = true).transactions.head
    val exchangeTxBlockBySignature = sender.blockById(sender.blockAt(exchangeTxHeight).id, amountsAsStrings = true).transactions.head
    val exchangeTxBlockSeq         = sender.blockSeq(exchangeTxHeight, exchangeTxHeight, amountsAsStrings = true).head.transactions.head
    checkExchangeTx(exchangeTxBlockLast)
    checkExchangeTx(exchangeTxBlockAt)
    checkExchangeTx(exchangeTxBlockBySignature)
    checkExchangeTx(exchangeTxBlockSeq)

    val exchangeTxInfo = sender.transactionInfo[TransactionInfo](exchangeTx.id, amountsAsStrings = true)
    exchangeTxInfo.amount shouldBe Some(amount)
    exchangeTxInfo.price shouldBe Some(price)
    exchangeTxInfo.sellMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.buyMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.sellOrderMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.buyOrderMatcherFee shouldBe Some(matcherFee)
    exchangeTxInfo.fee shouldBe matcherFee
  }

  test("amount as string in data transaction") {
    overflowBlock()
    val dataEntries = List(IntegerDataEntry("int", 666))
    val dataFee     = calcDataFee(dataEntries, TxVersion.V1)
    val dataTx      = sender.putData(sender.address, dataEntries, dataFee, amountsAsStrings = true)
    dataTx.fee shouldBe dataFee
    dataTx.data.map(d => d.filter(_.key == "int").head.value) shouldBe Some(666)

    sender.utx(amountsAsStrings = true).head.data.map(d => d.filter(_.key == "int").head.value) shouldBe Some(666)
    sender.utxById(dataTx.id, amountsAsStrings = true).data.map(d => d.filter(_.key == "int").head.value) shouldBe Some(666)

    val dataTxHeight = sender.waitForTransaction(dataTx.id).height

    def findValue(block: Block): Any =
      (for {
        tx   <- block.transactions
        data <- tx.data.getOrElse(Nil) if data.key == "int"
      } yield data.value).head

    val lastBlock = sender.lastBlock(amountsAsStrings = true)
    findValue(lastBlock) shouldBe 666
    findValue(sender.blockAt(dataTxHeight, amountsAsStrings = true)) shouldBe 666
    findValue(sender.blockById(sender.lastBlock().id, amountsAsStrings = true)) shouldBe 666
    findValue(
      sender
        .blockSeq(dataTxHeight, dataTxHeight, amountsAsStrings = true)
        .head
    ) shouldBe 666

    sender.transactionInfo[TransactionInfo](dataTx.id, amountsAsStrings = true).data.map(d => d.filter(_.key == "int").head.value) shouldBe Some(666)
    sender.getData(sender.address, amountsAsStrings = true).filter(_.key == "int").head.value shouldBe 666
  }

  test("amount as string in sponsorfee transaction") {
    nodes.waitForHeightArise()
    overflowBlock()
    def checkSponsorshipTx(tx: Transaction): Assertion = {
      tx.minSponsoredAssetFee shouldBe Some(10000)
      tx.fee shouldBe sponsorFee
    }
    val sponsoredAssetId = sender.issue(sender.address, "sponsor", "", someAssetAmount, 8, waitForTx = true).id
    nodes.waitForHeightArise()
    val sponsorshipTx = sender.sponsorAsset(sender.address, sponsoredAssetId, 10000, sponsorFee, amountsAsStrings = true)
    checkSponsorshipTx(sponsorshipTx)

    checkSponsorshipTx(sender.utx(amountsAsStrings = true).head)
    checkSponsorshipTx(sender.utxById(sponsorshipTx.id))

    val sponsorshipTxHeight           = sender.waitForTransaction(sponsorshipTx.id).height
    val sponsorshipTxBlockLast        = sender.lastBlock(amountsAsStrings = true).transactions.head
    val sponsorshipTxBlockAt          = sender.blockAt(sponsorshipTxHeight, amountsAsStrings = true).transactions.head
    val sponsorshipTxBlockBySignature = sender.blockById(sender.blockAt(sponsorshipTxHeight).id, amountsAsStrings = true).transactions.head
    val sponsorshipTxBlockSeq         = sender.blockSeq(sponsorshipTxHeight, sponsorshipTxHeight, amountsAsStrings = true).head.transactions.head
    checkSponsorshipTx(sponsorshipTxBlockLast)
    checkSponsorshipTx(sponsorshipTxBlockAt)
    checkSponsorshipTx(sponsorshipTxBlockBySignature)
    checkSponsorshipTx(sponsorshipTxBlockSeq)

    val sponsorshipTxInfo = sender.transactionInfo[TransactionInfo](sponsorshipTx.id)
    sponsorshipTxInfo.minSponsoredAssetFee shouldBe Some(10000)
    sponsorshipTxInfo.fee shouldBe sponsorFee
  }
  test("amount as string in masstransfer transaction") {
    nodes.waitForHeightArise()
    overflowBlock()
    def checkMassTransferTx(tx: Transaction): Assertion = {
      tx.transfers.get.head.amount shouldBe transferAmount
      tx.totalAmount shouldBe Some(transferAmount)
    }
    val (transfers, massTransferFee) = (List(Transfer(secondAddress, transferAmount)), calcMassTransferFee(1))
    val massTransferTx               = sender.massTransfer(firstAddress, transfers, massTransferFee, amountsAsStrings = true)
    checkMassTransferTx(massTransferTx)

    checkMassTransferTx(sender.utx(amountsAsStrings = true).head)
    checkMassTransferTx(sender.utxById(massTransferTx.id, amountsAsStrings = true))

    val massTransferTxHeight = sender.waitForTransaction(massTransferTx.id).height
    val massTransferTxBlockAt =
      sender.blockAt(massTransferTxHeight, amountsAsStrings = true).transactions.find(_._type == MassTransferTransaction.typeId).get
    val massTransferTxBlockBySignature = sender
      .blockById(sender.blockAt(massTransferTxHeight).id, amountsAsStrings = true)
      .transactions
      .find(_._type == MassTransferTransaction.typeId)
      .get
    val massTransferTxBlockSeq = sender
      .blockSeq(massTransferTxHeight, massTransferTxHeight, amountsAsStrings = true)
      .head
      .transactions
      .find(_._type == MassTransferTransaction.typeId)
      .get
    checkMassTransferTx(massTransferTxBlockAt)
    checkMassTransferTx(massTransferTxBlockBySignature)
    checkMassTransferTx(massTransferTxBlockSeq)

    val massTransferTxInfo = sender.transactionInfo[TransactionInfo](massTransferTx.id)
    massTransferTxInfo.transfers.get.head.amount shouldBe transferAmount
    massTransferTxInfo.totalAmount shouldBe Some(transferAmount)

    val tx =
      Json.obj(
        "type"            -> CreateAliasTransaction.typeId,
        "sender"          -> firstAddress,
        "alias"           -> "alias",
        "fee"             -> 100000,
        "timestamp"       -> System.currentTimeMillis(),
        "version"         -> 1,
        "senderPublicKey" -> Base58.encode(new Array[Byte](32))
      )
    sender.calculateFee(tx, amountsAsStrings = true).feeAmount shouldBe minFee
  }

  test("amount as string in blocks api") {
    nodes.waitForHeightArise()
    val currentHeight    = sender.height
    val reward           = sender.rewardStatus().currentReward
    val blockLast        = sender.lastBlock(amountsAsStrings = true)
    val blockAt          = sender.blockAt(currentHeight, amountsAsStrings = true)
    val blockBySignature = sender.blockById(sender.lastBlock().id, amountsAsStrings = true)
    val blockHeadersAt   = sender.blockHeadersAt(currentHeight, amountsAsStrings = true)
    val blockHeadersLast = sender.lastBlockHeader(amountsAsStrings = true)

    for (block <- Seq(blockLast, blockAt, blockBySignature)) {
      block.reward shouldBe Some(reward)
      block.desiredReward shouldBe Some(-1)
      block.totalFee shouldBe Some(0)
    }

    for (block <- Seq(blockHeadersLast, blockHeadersAt)) {
      block.reward shouldBe Some(reward)
      block.desiredReward shouldBe Some(-1)
      block.totalFee shouldBe 0
    }

    val blockSeq          = sender.blockSeq(currentHeight, currentHeight, amountsAsStrings = true)
    val blockSeqByAddress = sender.blockSeqByAddress(miner.address, currentHeight, currentHeight, amountsAsStrings = true)

    for (blocks <- Seq(blockSeq, blockSeqByAddress)) {
      blocks.head.reward shouldBe Some(reward)
      blocks.head.desiredReward shouldBe Some(-1)
      blocks.head.totalFee shouldBe Some(0)
    }

    val blockHeadersSeq = sender.blockHeadersSeq(currentHeight, currentHeight, amountsAsStrings = true)
    blockHeadersSeq.head.reward shouldBe Some(reward)
    blockHeadersSeq.head.desiredReward shouldBe Some(-1)
    blockHeadersSeq.head.totalFee shouldBe 0
  }

  test("amount as string in rewards api") {
    val currentHeight    = sender.height
    val rewardsAsInteger = sender.rewardStatus()
    val rewards          = sender.rewardStatus(amountsAsStrings = true)
    val rewardsByHeight  = sender.rewardStatus(Some(currentHeight), amountsAsStrings = true)
    rewards.totalWavesAmount shouldBe rewardsAsInteger.totalWavesAmount
    rewards.currentReward shouldBe rewardsAsInteger.currentReward
    rewards.minIncrement shouldBe rewardsAsInteger.minIncrement
    rewardsByHeight.totalWavesAmount shouldBe rewardsAsInteger.totalWavesAmount
    rewardsByHeight.currentReward shouldBe rewardsAsInteger.currentReward
    rewardsByHeight.minIncrement shouldBe rewardsAsInteger.minIncrement
  }

  test("amount as string in consensus api") {
    val firstGeneratingBalance = sender.balanceDetails(firstAddress).generating
    sender.generatingBalance(firstAddress, amountsAsStrings = true).balance shouldBe firstGeneratingBalance
  }

  test("amount as string in debug api") {
    val firstBalance = sender.balanceDetails(firstAddress).available
    val portfolio    = sender.debugPortfoliosFor(firstAddress, considerUnspent = false, amountsAsStrings = true)
    portfolio.balance shouldBe firstBalance
    portfolio.lease.in shouldBe 0
    portfolio.lease.out shouldBe 0

    sender.debugBalanceHistory(firstAddress, amountsAsStrings = true).head.balance shouldBe firstBalance

    val stateWavesOnHeight = sender.getWithCustomHeader(
      s"/debug/stateWaves/${sender.height}",
      headerValue = "application/json;large-significand-format=string",
      withApiKey = true
    )
    (parseResponse(stateWavesOnHeight) \ s"$firstAddress").as[String] shouldBe s"$firstBalance"
  }

  private def parseResponse(response: Response): JsValue = Json.parse(response.getResponseBody)
}
