package io.provenance.explorer.domain.entities

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.gov.v1beta1.Gov
import cosmos.gov.v1beta1.Tx
import io.provenance.explorer.OBJECT_MAPPER
import io.provenance.explorer.domain.core.sql.jsonb
import io.provenance.explorer.domain.models.explorer.GovAddrData
import io.provenance.explorer.domain.models.explorer.GovTxData
import io.provenance.explorer.domain.models.explorer.VoteDbRecord
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


object GovProposalTable : IntIdTable(name = "gov_proposal") {
    val proposalId = long("proposal_id")
    val proposalType = varchar("proposal_type", 128)
    val addressId = integer("address_id")
    val address = varchar("address", 128)
    val isValidator = bool("is_validator").default(false)
    val title = text("title")
    val description = text("description")
    val status = varchar("status", 128)
    val data = jsonb<GovProposalTable, Gov.Proposal>("data", OBJECT_MAPPER)
    val content = jsonb<GovProposalTable, ObjectNode>("content", OBJECT_MAPPER)
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txTimestamp = datetime("tx_timestamp")
}

fun String.getProposalType() = this.split(".").last().replace("Proposal", "")

fun Message.toObjectNode(protoPrinter: JsonFormat.Printer) =
    OBJECT_MAPPER.readValue(protoPrinter.print(this), ObjectNode::class.java)
        .let { it.get("title").asText() to it.get("description").asText() }

fun Message.toProposalContent(protoPrinter: JsonFormat.Printer) =
    OBJECT_MAPPER.readValue(protoPrinter.print(this), ObjectNode::class.java)
        .let { node ->
            node.remove("title")
            node.remove("description")
            node }

class GovProposalRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GovProposalRecord>(GovProposalTable) {

        fun getAllCount() = transaction { GovProposalRecord.all().count() }

        fun getAllPaginated(offset: Int, limit: Int) = transaction {
            GovProposalRecord.all()
                .orderBy(Pair(GovProposalTable.proposalId, SortOrder.DESC))
                .limit(limit, offset.toLong())
                .toList()
        }

        fun findByProposalId(proposalId: Long) = transaction {
            GovProposalRecord.find { GovProposalTable.proposalId eq proposalId }.firstOrNull()
        }

        fun getOrInsert(
            proposal: Gov.Proposal,
            protoPrinter: JsonFormat.Printer,
            txInfo: GovTxData,
            addrInfo: GovAddrData
        ) =
            transaction {
                findByProposalId(proposal.proposalId)?.apply {
                    this.status = proposal.status.name
                    this.data = proposal
                } ?: GovProposalTable.insertAndGetId {
                    it[this.proposalId] = proposal.proposalId
                    it[this.proposalType] = proposal.content.typeUrl.getProposalType()
                    it[this.address] = addrInfo.addr
                    it[this.addressId] = addrInfo.addrId
                    it[this.isValidator] = addrInfo.isValidator
                    proposal.content.toObjectNode(protoPrinter).let { pair ->
                        it[this.title] = pair.first
                        it[this.description] = pair.second
                    }
                    it[this.status] = proposal.status.name
                    it[this.data] = proposal
                    it[this.content] = proposal.content.toProposalContent(protoPrinter)
                    it[this.blockHeight] = txInfo.blockHeight
                    it[this.txHash] = txInfo.txHash
                    it[this.txTimestamp] = txInfo.txTimestamp
                }.let { GovProposalRecord.findById(it)!! }
            }
    }

    var proposalId by GovProposalTable.proposalId
    var proposalType by GovProposalTable.proposalType
    var addressId by GovProposalTable.addressId
    var address by GovProposalTable.address
    var isValidator by GovProposalTable.isValidator
    var title by GovProposalTable.title
    var description by GovProposalTable.description
    var status by GovProposalTable.status
    var data by GovProposalTable.data
    var content by GovProposalTable.content
    var blockHeight by GovProposalTable.blockHeight
    var txHash by GovProposalTable.txHash
    var txTimestamp by GovProposalTable.txTimestamp
}

object GovVoteTable : IntIdTable(name = "gov_vote") {
    val proposalId = long("proposal_id")
    val addressId = integer("address_id")
    val address = varchar("address", 128)
    val isValidator = bool("is_validator").default(false)
    val vote = varchar("vote", 128)
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txTimestamp = datetime("tx_timestamp")
}

class GovVoteRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GovVoteRecord>(GovVoteTable) {

        fun findByProposalId(proposalId: Long) = transaction {
            GovVoteRecord.find { GovVoteTable.proposalId eq proposalId }
                .orderBy(Pair(GovVoteTable.blockHeight, SortOrder.DESC))
                .map {
                    VoteDbRecord(
                        it.address,
                        it.isValidator,
                        it.vote,
                        it.blockHeight,
                        it.txHash,
                        it.txTimestamp,
                        it.proposalId,
                        "",
                        "") }
        }

        fun getByAddrIdPaginated(addrId: Int, limit: Int, offset: Int) = transaction {
            GovVoteTable.innerJoin(GovProposalTable, { GovVoteTable.proposalId }, { GovProposalTable.proposalId })
                .select { GovVoteTable.addressId eq addrId }
                .orderBy(Pair(GovVoteTable.proposalId, SortOrder.DESC))
                .limit(limit, offset.toLong())
                .map {
                    VoteDbRecord(
                        it[GovVoteTable.address],
                        it[GovVoteTable.isValidator],
                        it[GovVoteTable.vote],
                        it[GovVoteTable.blockHeight],
                        it[GovVoteTable.txHash],
                        it[GovVoteTable.txTimestamp],
                        it[GovVoteTable.proposalId],
                        it[GovProposalTable.title],
                        it[GovProposalTable.status]) }
        }

        fun getByAddrIdCount(addrId: Int) = transaction {
            GovVoteRecord.find { GovVoteTable.addressId eq addrId }.count()
        }

        fun findByProposalIdAndAddrId(proposalId: Long, addrId: Int) = transaction {
            GovVoteRecord
                .find { (GovVoteTable.proposalId eq proposalId) and (GovVoteTable.addressId eq addrId) }
                .firstOrNull()
        }

        fun getOrInsert(
            txInfo: GovTxData,
            vote: Tx.MsgVote,
            addrInfo: GovAddrData
        ) = transaction {
                findByProposalIdAndAddrId(vote.proposalId, addrInfo.addrId)
                    ?.apply {
                        this.vote = vote.option.name
                        this.blockHeight = blockHeight
                        this.txHash = txHash
                        this.txTimestamp = txTimestamp
                    } ?: GovVoteTable.insertAndGetId {
                        it[this.proposalId] = vote.proposalId
                        it[this.addressId] = addrInfo.addrId
                        it[this.address] = vote.voter
                        it[this.isValidator] = addrInfo.isValidator
                        it[this.vote] = vote.option.name
                        it[this.blockHeight] = txInfo.blockHeight
                        it[this.txHash] = txInfo.txHash
                        it[this.txTimestamp] = txInfo.txTimestamp
                }.let { GovVoteRecord.findById(it)!! }
            }
    }

    var proposalId by GovVoteTable.proposalId
    var addressId by GovVoteTable.addressId
    var address by GovVoteTable.address
    var isValidator by GovVoteTable.isValidator
    var vote by GovVoteTable.vote
    var blockHeight by GovVoteTable.blockHeight
    var txHash by GovVoteTable.txHash
    var txTimestamp by GovVoteTable.txTimestamp
}

object GovDepositTable : IntIdTable(name = "gov_deposit") {
    val proposalId = long("proposal_id")
    val addressId = integer("address_id")
    val address = varchar("address", 128)
    val isValidator = bool("is_validator").default(false)
    val depositType = varchar("deposit_type", 128)
    val amount = decimal("amount", 100,10)
    val denom = varchar("denom", 256)
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txTimestamp = datetime("tx_timestamp")
}

enum class DepositType { DEPOSIT, INITIAL_DEPOSIT }

class GovDepositRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GovDepositRecord>(GovDepositTable) {

        fun findByProposalId(proposalId: Long) = transaction {
            GovDepositRecord.find { GovDepositTable.proposalId eq proposalId }.toMutableList()
        }

        fun getByProposalIdPaginated(proposalId: Long, limit: Int, offset: Int) = transaction {
            GovDepositRecord.find { GovDepositTable.proposalId eq proposalId }
                .orderBy(Pair(GovDepositTable.blockHeight, SortOrder.DESC))
                .limit(limit, offset.toLong())
                .toList()
        }

        fun getByProposalIdCount(proposalId: Long) = transaction {
            GovDepositRecord.find { GovDepositTable.proposalId eq proposalId }.count()
        }

        fun insertAndGet(
            txInfo: GovTxData,
            proposalId: Long,
            depositType: DepositType,
            amountList: List<CoinOuterClass.Coin>,
            addrInfo: GovAddrData
        ) = transaction {
            amountList.forEach { amount ->
                GovDepositTable.insertAndGetId {
                    it[this.proposalId] = proposalId
                    it[this.addressId] = addrInfo.addrId
                    it[this.address] = addrInfo.addr
                    it[this.isValidator] = addrInfo.isValidator
                    it[this.depositType] = depositType.name
                    it[this.amount] = amount.amount.toBigDecimal()
                    it[this.denom] = amount.denom
                    it[this.blockHeight] = txInfo.blockHeight
                    it[this.txHash] = txInfo.txHash
                    it[this.txTimestamp] = txInfo.txTimestamp
                }.let { GovDepositRecord.findById(it)!! }
            }
        }
    }

    var proposalId by GovDepositTable.proposalId
    var addressId by GovDepositTable.addressId
    var address by GovDepositTable.address
    var isValidator by GovDepositTable.isValidator
    var depositType by GovDepositTable.depositType
    var amount by GovDepositTable.amount
    var denom by GovDepositTable.denom
    var blockHeight by GovDepositTable.blockHeight
    var txHash by GovDepositTable.txHash
    var txTimestamp by GovDepositTable.txTimestamp
}
