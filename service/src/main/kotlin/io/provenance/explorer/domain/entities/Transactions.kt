package io.provenance.explorer.domain.entities

import com.google.protobuf.Any
import cosmos.tx.v1beta1.ServiceOuterClass
import io.provenance.explorer.OBJECT_MAPPER
import io.provenance.explorer.domain.core.sql.Distinct
import io.provenance.explorer.domain.core.sql.jsonb
import io.provenance.explorer.domain.core.sql.toProcedureObject
import io.provenance.explorer.domain.entities.FeeType.BASE_FEE_OVERAGE
import io.provenance.explorer.domain.entities.FeeType.BASE_FEE_USED
import io.provenance.explorer.domain.entities.FeeType.MSG_BASED_FEE
import io.provenance.explorer.domain.entities.FeeType.PRIORITY_FEE
import io.provenance.explorer.domain.extensions.NHASH
import io.provenance.explorer.domain.extensions.exec
import io.provenance.explorer.domain.extensions.map
import io.provenance.explorer.domain.extensions.startOfDay
import io.provenance.explorer.domain.extensions.toCoinStr
import io.provenance.explorer.domain.extensions.toDateTime
import io.provenance.explorer.domain.extensions.toDbHash
import io.provenance.explorer.domain.models.explorer.GasStats
import io.provenance.explorer.domain.models.explorer.TxData
import io.provenance.explorer.domain.models.explorer.TxFee
import io.provenance.explorer.domain.models.explorer.TxFeepayer
import io.provenance.explorer.domain.models.explorer.TxGasVolume
import io.provenance.explorer.domain.models.explorer.TxQueryParams
import io.provenance.explorer.domain.models.explorer.TxStatus
import io.provenance.explorer.domain.models.explorer.TxUpdate
import io.provenance.explorer.domain.models.explorer.getCategoryForType
import io.provenance.explorer.domain.models.explorer.onlyTxQuery
import io.provenance.explorer.domain.models.explorer.toProcedureObject
import io.provenance.explorer.service.AssetService
import net.pearx.kasechange.toTitleCase
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ColumnSet
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.jodatime.DateColumnType
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.math.BigDecimal

object TxCacheTable : IntIdTable(name = "tx_cache") {
    val hash = varchar("hash", 64)
    val height = reference("height", BlockCacheTable.height)
    val gasWanted = integer("gas_wanted")
    val gasUsed = integer("gas_used")
    val txTimestamp = datetime("tx_timestamp")
    val errorCode = integer("error_code").nullable()
    val codespace = varchar("codespace", 16).nullable()
    val txV2 = jsonb<TxCacheTable, ServiceOuterClass.GetTxResponse>("tx_v2", OBJECT_MAPPER)
}

class TxCacheRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxCacheRecord>(TxCacheTable) {

        fun insertToProcedure(txUpdate: TxUpdate, height: Int, timestamp: DateTime) = transaction {
            val txStr = txUpdate.toProcedureObject()
            val query = "CALL add_tx($txStr, $height, '${timestamp.toProcedureObject()}')"
            this.exec(query)
        }

        fun buildInsert(tx: ServiceOuterClass.GetTxResponse, txTime: DateTime) =
            listOf(
                tx.txResponse.txhash,
                tx.txResponse.height.toInt(),
                tx.txResponse.gasWanted.toInt(),
                tx.txResponse.gasUsed.toInt(),
                txTime,
                if (tx.txResponse.code > 0) tx.txResponse.code else null,
                if (tx.txResponse.codespace.isNotBlank()) tx.txResponse.codespace else null,
                tx,
                0
            ).toProcedureObject()

        fun findByHeight(height: Int) =
            TxCacheRecord.find { TxCacheTable.height eq height }

        fun findByHash(hash: String) = transaction { TxCacheRecord.find { TxCacheTable.hash eq hash } }.firstOrNull()

        fun findSigsByHash(hash: String) = transaction { SignatureRecord.findByJoin(SigJoinType.TRANSACTION, hash) }

        fun getTotalTxCount() = transaction {
            TxCacheTable.selectAll().count().toBigInteger()
        }

        fun findByQueryForResults(txQueryParams: TxQueryParams) = transaction {
            val columns: MutableList<Expression<*>> = mutableListOf()
            if (!txQueryParams.onlyTxQuery())
                columns.add(Distinct(TxCacheTable.id, IntegerColumnType()).alias("dist"))
            columns.addAll(TxCacheTable.columns.toMutableList())
            val query =
                findByQueryParams(txQueryParams, columns)
                    .orderBy(Pair(TxCacheTable.height, SortOrder.DESC))
                    .limit(txQueryParams.count, txQueryParams.offset.toLong())
            TxCacheRecord.wrapRows(query).toSet()
        }

        fun findByQueryParamsForCount(txQueryParams: TxQueryParams) = transaction {
            if (txQueryParams.onlyTxQuery()) {
                findByQueryParams(txQueryParams, null).count().toBigInteger()
            } else {
                val distinctCount = TxCacheTable.id.countDistinct()
                findByQueryParams(txQueryParams, listOf(distinctCount)).first()[distinctCount].toBigInteger()
            }
        }

        private fun findByQueryParams(tqp: TxQueryParams, distinctQuery: List<Expression<*>>?) = transaction {
            var join: ColumnSet = TxCacheTable

            if (tqp.msgTypes.isNotEmpty())
                join = join.innerJoin(TxMessageTable, { TxCacheTable.id }, { TxMessageTable.txHashId })
            if ((tqp.addressId != null && tqp.addressType != null) || tqp.address != null)
                join = join.innerJoin(TxAddressJoinTable, { TxCacheTable.id }, { TxAddressJoinTable.txHashId })
            if (tqp.markerId != null || tqp.denom != null)
                join = join.innerJoin(TxMarkerJoinTable, { TxCacheTable.id }, { TxMarkerJoinTable.txHashId })
            if (tqp.nftId != null)
                join = join.innerJoin(TxNftJoinTable, { TxCacheTable.id }, { TxNftJoinTable.txHashId })

            val query = if (distinctQuery != null) join.slice(distinctQuery).selectAll() else join.selectAll()

            if (tqp.msgTypes.isNotEmpty())
                query.andWhere { TxMessageTable.txMessageType inList tqp.msgTypes }
            if (tqp.txHeight != null)
                query.andWhere { TxCacheTable.height eq tqp.txHeight }
            if (tqp.txStatus != null)
                query.andWhere {
                    if (tqp.txStatus == TxStatus.FAILURE) TxCacheTable.errorCode neq 0 else TxCacheTable.errorCode.isNull()
                }
            if (tqp.addressId != null && tqp.addressType != null)
                query.andWhere { (TxAddressJoinTable.addressId eq tqp.addressId) and (TxAddressJoinTable.addressType eq tqp.addressType) }
            else if (tqp.address != null)
                query.andWhere { (TxAddressJoinTable.address eq tqp.address) }
            if (tqp.markerId != null)
                query.andWhere { TxMarkerJoinTable.markerId eq tqp.markerId }
            else if (tqp.denom != null)
                query.andWhere { TxMarkerJoinTable.denom eq tqp.denom }
            if (tqp.nftId != null && tqp.nftType != null)
                query.andWhere { (TxNftJoinTable.metadataId eq tqp.nftId) and (TxNftJoinTable.metadataType eq tqp.nftType) }
            else if (tqp.nftUuid != null && tqp.nftType != null)
                query.andWhere { (TxNftJoinTable.metadataUuid eq tqp.nftUuid) and (TxNftJoinTable.metadataType eq tqp.nftType) }
            if (tqp.fromDate != null)
                query.andWhere { TxCacheTable.txTimestamp greaterEq tqp.fromDate.startOfDay() }
            if (tqp.toDate != null)
                query.andWhere { TxCacheTable.txTimestamp lessEq tqp.toDate.startOfDay().plusDays(1) }

            query
        }
    }

    var hash by TxCacheTable.hash
    var height by TxCacheTable.height
    var gasWanted by TxCacheTable.gasWanted
    var gasUsed by TxCacheTable.gasUsed
    var txTimestamp by TxCacheTable.txTimestamp
    var errorCode by TxCacheTable.errorCode
    var codespace by TxCacheTable.codespace
    var txV2 by TxCacheTable.txV2
    val txMessages by TxMessageRecord referrersOn TxMessageTable.txHashId
    val txFees by TxFeeRecord referrersOn TxFeeTable.txHashId
    val txFeepayer by TxFeepayerRecord referrersOn TxFeepayerTable.txHashId
}

object TxMessageTypeTable : IntIdTable(name = "tx_message_type") {
    val type = varchar("type", 128)
    val module = varchar("module", 128)
    val protoType = varchar("proto_type", 256)
    val category = varchar("category", 128).nullable()
}

const val UNKNOWN = "unknown"

class TxMessageTypeRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxMessageTypeRecord>(TxMessageTypeTable) {

        fun findByProtoType(protoType: String) = transaction {
            TxMessageTypeRecord.find { TxMessageTypeTable.protoType eq protoType }.firstOrNull()
        }

        fun findByType(types: List<String>) = transaction {
            TxMessageTypeRecord.find { TxMessageTypeTable.type inList types }
        }

        fun findByIdIn(idList: List<Int>) = transaction {
            TxMessageTypeRecord.find { TxMessageTypeTable.id inList idList }.toList()
        }

        fun insert(type: String, module: String, protoType: String) = transaction {
            findByProtoType(protoType)?.apply {
                if (this.type == UNKNOWN) this.type = type
                if (this.module == UNKNOWN) this.module = module
                if (type.getCategoryForType() != null) this.category = type.getCategoryForType()!!.mainCategory
            }?.id ?: TxMessageTypeTable.insertAndGetId {
                it[this.type] = type
                it[this.module] = module
                it[this.protoType] = protoType
                if (type.getCategoryForType() != null)
                    it[this.category] = type.getCategoryForType()!!.mainCategory
            }
        }
    }

    var type by TxMessageTypeTable.type
    var module by TxMessageTypeTable.module
    var protoType by TxMessageTypeTable.protoType
    var category by TxMessageTypeTable.category
}

object TxMessageTable : IntIdTable(name = "tx_message") {
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txHashId = reference("tx_hash_id", TxCacheTable)
    val txMessageType = reference("tx_message_type_id", TxMessageTypeTable)
    val txMessage = jsonb<TxMessageTable, Any>("tx_message", OBJECT_MAPPER)
    val txMessageHash = text("tx_message_hash")
    val msgIdx = integer("msg_idx")
}

class TxMessageRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxMessageRecord>(TxMessageTable) {

        fun findByHash(hash: String) = transaction {
            TxMessageRecord.find { TxMessageTable.txHash eq hash }
        }

        fun getCountByHashId(hashId: Int, msgTypes: List<Int>) = transaction {
            TxMessageRecord.find {
                (TxMessageTable.txHashId eq hashId) and
                    (if (msgTypes.isNotEmpty()) (TxMessageTable.txMessageType inList msgTypes) else (Op.TRUE))
            }
                .count()
        }

        fun findByHashIdPaginated(hashId: Int, msgTypes: List<Int>, limit: Int, offset: Int) = transaction {
            TxMessageRecord.find {
                (TxMessageTable.txHashId eq hashId) and
                    (if (msgTypes.isNotEmpty()) (TxMessageTable.txMessageType inList msgTypes) else (Op.TRUE))
            }
                .orderBy(Pair(TxMessageTable.msgIdx, SortOrder.ASC))
                .limit(limit, offset.toLong())
                .toMutableList()
        }

        fun getDistinctTxMsgTypesByTxHash(txHashId: EntityID<Int>) = transaction {
            val msgIdDist = Distinct(TxMessageTable.txMessageType, IntegerColumnType()).alias("dist")
            TxMessageTable.slice(msgIdDist, TxMessageTable.txMessageType).select { TxMessageTable.txHashId eq txHashId }
                .map { it[TxMessageTable.txMessageType].value }
        }

        fun findByQueryForResults(txQueryParams: TxQueryParams) = transaction {
            val columns = TxMessageTable.columns.toMutableList()
            val query =
                findByQueryParams(txQueryParams, columns)
                    .orderBy(Pair(TxMessageTable.blockHeight, SortOrder.DESC))
                    .limit(txQueryParams.count, txQueryParams.offset.toLong())
            TxMessageRecord.wrapRows(query).toSet()
        }

        fun findByQueryParamsForCount(txQueryParams: TxQueryParams) = transaction {
            val distinctCount = TxMessageTable.id.countDistinct()
            findByQueryParams(txQueryParams, listOf(distinctCount)).first()[distinctCount].toBigInteger()
        }

        private fun findByQueryParams(tqp: TxQueryParams, distinctQuery: List<Expression<*>>?) = transaction {
            var join: ColumnSet = TxCacheTable

            if (tqp.msgTypes.isNotEmpty())
                join = join.innerJoin(TxMessageTable, { TxCacheTable.id }, { TxMessageTable.txHashId })
            if ((tqp.addressId != null && tqp.addressType != null) || tqp.address != null)
                join = join.innerJoin(TxAddressJoinTable, { TxCacheTable.id }, { TxAddressJoinTable.txHashId })
            if (tqp.smCodeId != null)
                join = join.innerJoin(TxSmCodeTable, { TxCacheTable.id }, { TxSmCodeTable.txHashId })
            if (tqp.smContractAddrId != null)
                join = join.innerJoin(TxSmContractTable, { TxCacheTable.id }, { TxSmContractTable.txHashId })

            val query = if (distinctQuery != null) join.slice(distinctQuery).selectAll() else join.selectAll()

            if (tqp.msgTypes.isNotEmpty())
                query.andWhere { TxMessageTable.txMessageType inList tqp.msgTypes }
            if (tqp.txHeight != null)
                query.andWhere { TxCacheTable.height eq tqp.txHeight }
            if (tqp.txStatus != null)
                query.andWhere {
                    if (tqp.txStatus == TxStatus.FAILURE) TxCacheTable.errorCode neq 0 else TxCacheTable.errorCode.isNull()
                }
            if (tqp.addressId != null && tqp.addressType != null)
                query.andWhere { (TxAddressJoinTable.addressId eq tqp.addressId) and (TxAddressJoinTable.addressType eq tqp.addressType) }
            else if (tqp.address != null)
                query.andWhere { (TxAddressJoinTable.address eq tqp.address) }
            if (tqp.smCodeId != null)
                query.andWhere { (TxSmCodeTable.smCode eq tqp.smCodeId) }
            if (tqp.smContractAddrId != null)
                query.andWhere { (TxSmContractTable.contractId eq tqp.smContractAddrId) }
            if (tqp.fromDate != null)
                query.andWhere { TxCacheTable.txTimestamp greaterEq tqp.fromDate.startOfDay() }
            if (tqp.toDate != null)
                query.andWhere { TxCacheTable.txTimestamp lessEq tqp.toDate.startOfDay().plusDays(1) }

            query
        }

        fun buildInsert(
            blockHeight: Int,
            txHash: String,
            message: Any,
            type: String,
            module: String,
            msgIdx: Int
        ) = transaction {
            TxMessageTypeRecord.insert(type, module, message.typeUrl).let { typeId ->
                listOf(
                    0,
                    blockHeight,
                    txHash,
                    typeId.value,
                    message,
                    message.value.toDbHash(),
                    0,
                    msgIdx
                ).toProcedureObject() to typeId.value
            }
        }
    }

    var blockHeight by TxMessageTable.blockHeight
    var txHashId by TxCacheRecord referencedOn TxMessageTable.txHashId
    var txHash by TxMessageTable.txHash
    var txMessageType by TxMessageTypeRecord referencedOn TxMessageTable.txMessageType
    var txMessage by TxMessageTable.txMessage
    var txMessageHash by TxMessageTable.txMessageHash
    var msgIdx by TxMessageTable.msgIdx
}

object TxEventsTable : IntIdTable(name = "tx_msg_event") {
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txHashId = reference("tx_hash_id", TxCacheTable)
    val txMessageId = integer("tx_message_id")
    val txMsgTypeId = integer("tx_msg_type_id")
    val eventType = varchar("event_type", 256)
}

class TxEventRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxEventRecord>(TxEventsTable) {

        fun buildInsert(blockHeight: Int, txHash: String, type: String, msgTypeId: Int) =
            listOf(0, blockHeight, 0, txHash, 0, type, msgTypeId).toProcedureObject()
    }

    var blockHeight by TxEventsTable.blockHeight
    var txHash by TxEventsTable.txHash
    var txHashId by TxEventsTable.txHashId
    var txMessageId by TxEventsTable.txMessageId
    var txMsgTypeId by TxEventsTable.txMsgTypeId
    var eventType by TxEventsTable.eventType
}

object TxEventAttrTable : IntIdTable(name = "tx_msg_event_attr") {
    val txMsgEventId = integer("tx_msg_event_id")
    val attrKey = varchar("attr_key", 256)
    val attrValue = text("attr_value")
    val attrIdx = integer("attr_idx")
    val attrHash = text("attr_hash")
}

class TxEventAttrRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxEventAttrRecord>(TxEventAttrTable) {

        fun buildInsert(idx: Int, key: String, value: String) =
            listOf(0, 0, key, value, idx, listOf(idx, key, value).joinToString("").toDbHash()).toProcedureObject()
    }

    var txMsgEventId by TxEventAttrTable.txMsgEventId
    var attrKey by TxEventAttrTable.attrKey
    var attrValue by TxEventAttrTable.attrValue
    var attrIdx by TxEventAttrTable.attrIdx
    var attrHash by TxEventAttrTable.attrHash
}

object TxSingleMessageCacheTable : IntIdTable(name = "tx_single_message_cache") {
    val txTimestamp = datetime("tx_timestamp")
    val txHash = varchar("tx_hash", 64)
    val gasUsed = integer("gas_used")
    val txMessageType = varchar("tx_message_type", 128)
    val processed = bool("processed")
}

class TxSingleMessageCacheRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxSingleMessageCacheRecord>(TxSingleMessageCacheTable) {

        fun buildInsert(txTime: DateTime, txHash: String, gasUsed: Int, type: String) =
            listOf(0, txTime, txHash, gasUsed, type, false).toProcedureObject()

        fun getGasStats(fromDate: DateTime, toDate: DateTime, granularity: String) = transaction {
            val tblName = "tx_single_message_gas_stats_${granularity.lowercase()}"
            val query = """
                |SELECT
                |   $tblName.tx_timestamp,
                |   $tblName.min_gas_used,
                |   $tblName.max_gas_used,
                |   $tblName.avg_gas_used,
                |   $tblName.stddev_gas_used,
                |   $tblName.tx_message_type
                |FROM $tblName
                |WHERE $tblName.tx_timestamp >= ? 
                |  AND $tblName.tx_timestamp < ?
                |ORDER BY $tblName.tx_timestamp ASC
                |""".trimMargin()

            val dateTimeType = DateColumnType(true)
            val arguments = listOf<Pair<DateColumnType, DateTime>>(
                Pair(dateTimeType, fromDate.startOfDay()),
                Pair(dateTimeType, toDate.startOfDay().plusDays(1))
            )

            val tz = DateTimeZone.UTC
            val pattern = "yyyy-MM-dd HH:mm:ss"

            query.exec(arguments)
                .map {
                    GasStats(
                        it.getTimestamp("tx_timestamp").toDateTime(tz, pattern),
                        it.getInt("min_gas_used"),
                        it.getInt("max_gas_used"),
                        it.getInt("avg_gas_used"),
                        it.getInt("stddev_gas_used"),
                        it.getString("tx_message_type")
                    )
                }
        }

        fun updateGasStats(): Unit = transaction {
            val conn = TransactionManager.current().connection
            val queries = listOf("CALL update_gas_fee_stats()")
            conn.executeInBatch(queries)
        }
    }

    var txTimestamp by TxSingleMessageCacheTable.txTimestamp
    var txHash by TxSingleMessageCacheTable.txHash
    var gasUsed by TxSingleMessageCacheTable.gasUsed
    var txMessageType by TxSingleMessageCacheTable.txMessageType
    var processed by TxSingleMessageCacheTable.processed
}

object TxGasCacheTable : IntIdTable(name = "tx_gas_cache") {
    val hash = varchar("hash", 64)
    val txTimestamp = datetime("tx_timestamp")
    val gasWanted = integer("gas_wanted").nullable()
    val gasUsed = integer("gas_used")
    val feeAmount = double("fee_amount").nullable()
    val processed = bool("processed").default(false)
}

class TxGasCacheRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxGasCacheRecord>(TxGasCacheTable) {

        fun buildInsert(tx: ServiceOuterClass.GetTxResponse, txTime: DateTime) =
            TxFeeRecord.calcBaseGasFee(tx.txResponse.gasWanted).toDouble().let {
                listOf(
                    0,
                    tx.txResponse.txhash,
                    txTime,
                    tx.txResponse.gasWanted.toInt(),
                    tx.txResponse.gasUsed.toInt(),
                    it,
                    false
                ).toProcedureObject()
            }

        fun getGasVolume(fromDate: DateTime, toDate: DateTime, granularity: String) = transaction {
            val tblName = "tx_gas_fee_volume_${granularity.lowercase()}"
            val query = """
                |SELECT
                |   $tblName.tx_timestamp,
                |   $tblName.gas_wanted,
                |   $tblName.gas_used,
                |   $tblName.fee_amount
                |FROM $tblName
                |WHERE $tblName.tx_timestamp >= ? 
                |  AND $tblName.tx_timestamp < ?
                |ORDER BY $tblName.tx_timestamp ASC
                |""".trimMargin()

            val dateTimeType = DateColumnType(true)
            val arguments = listOf<Pair<DateColumnType, DateTime>>(
                Pair(dateTimeType, fromDate.startOfDay()),
                Pair(dateTimeType, toDate.startOfDay().plusDays(1))
            )

            val tz = DateTimeZone.UTC
            val pattern = "yyyy-MM-dd HH:mm:ss"

            query.exec(arguments)
                .map {
                    TxGasVolume(
                        it.getTimestamp("tx_timestamp").toDateTime(tz, pattern),
                        it.getBigDecimal("gas_wanted").toBigInteger(),
                        it.getBigDecimal("gas_used").toBigInteger(),
                        it.getBigDecimal("fee_amount")
                    )
                }
        }

        fun updateGasFeeVolume(): Unit = transaction {
            val conn = TransactionManager.current().connection
            val queries = listOf("CALL update_gas_fee_volume()")
            conn.executeInBatch(queries)
        }
    }

    var hash by TxGasCacheTable.hash
    var txTimestamp by TxGasCacheTable.txTimestamp
    var gasWanted by TxGasCacheTable.gasWanted
    var gasUsed by TxGasCacheTable.gasUsed
    var feeAmount by TxGasCacheTable.feeAmount
    var processed by TxGasCacheTable.processed
}

object TxFeepayerTable : IntIdTable(name = "tx_feepayer") {
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txHashId = reference("tx_hash_id", TxCacheTable)
    val payerType = varchar("payer_type", 128)
    val addressId = integer("address_id")
    val address = varchar("address", 128)
}

enum class FeePayer { GRANTER, PAYER, FIRST_SIGNER }

fun SizedIterable<TxFeepayerRecord>.getFeepayer() = this.map { TxFeepayer(it.payerType, it.address) }
    .minByOrNull { FeePayer.valueOf(it.type).ordinal }!!

class TxFeepayerRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxFeepayerRecord>(TxFeepayerTable) {

        fun buildInsert(txInfo: TxData, type: String, addrId: Int, address: String) =
            listOf(0, txInfo.blockHeight, -1, txInfo.txHash, type, addrId, address).toProcedureObject()
    }

    var blockHeight by TxFeepayerTable.blockHeight
    var txHash by TxFeepayerTable.txHash
    var txHashId by TxCacheRecord referencedOn TxFeepayerTable.txHashId
    var payerType by TxFeepayerTable.payerType
    var addressId by TxFeepayerTable.addressId
    var address by TxFeepayerTable.address
}

object TxFeeTable : IntIdTable(name = "tx_fee") {
    val blockHeight = integer("block_height")
    val txHash = varchar("tx_hash", 64)
    val txHashId = reference("tx_hash_id", TxCacheTable)
    val feeType = varchar("fee_type", 128)
    val markerId = integer("marker_id")
    val marker = varchar("marker", 256)
    val amount = decimal("amount", 100, 10)
}

enum class FeeType { BASE_FEE_USED, BASE_FEE_OVERAGE, PRIORITY_FEE, MSG_BASED_FEE }

fun List<TxFeeRecord>.toFees() = this.groupBy { it.feeType }
    .map { (k, v) -> TxFee(k.toTitleCase(), v.map { it.amount.toCoinStr(it.marker) }) }

fun List<TxFeeRecord>.toFeePaid() = this.sumOf { it.amount }.toCoinStr(this.first().marker)

class TxFeeRecord(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TxFeeRecord>(TxFeeTable) {

        // TODO: Replace 1905 with value from chain when 1.8.0 gets released
        fun calcBaseGasFee(gasWanted: Long) = gasWanted * 1905

        fun buildInserts(txInfo: TxData, tx: ServiceOuterClass.GetTxResponse, assetService: AssetService) =
            transaction {
                val success = tx.txResponse.code == 0
                val feeList = mutableListOf<String>()
                // calc baseFeeUsed, baseFeeOverage in nhash, and remaining as priority in nhash
                tx.tx.authInfo.fee.amountList.first { it.denom == NHASH }.let {
                    val baseFee = calcBaseGasFee(tx.txResponse.gasWanted)
                    val baseFeeUsed = calcBaseGasFee(tx.txResponse.gasUsed)
                    Triple(it.amount.toLong() - baseFee, baseFee - baseFeeUsed, baseFeeUsed)
                }.let { (priority, baseFeeOverage, baseFeeUsed) ->
                    val nhash = assetService.getAssetRaw(NHASH).second
                    // insert used fee
                    feeList.add(
                        buildInsert(txInfo, BASE_FEE_USED.name, nhash.id.value, nhash.denom, baseFeeUsed.toBigDecimal())
                    )
                    // insert paid too much fee
                    feeList.add(
                        buildInsert(
                            txInfo,
                            BASE_FEE_OVERAGE.name,
                            nhash.id.value,
                            nhash.denom,
                            baseFeeOverage.toBigDecimal()
                        )
                    )
                    // insert any overage as priority for now
                    if (priority > 0)
                        feeList.add(
                            buildInsert(txInfo, PRIORITY_FEE.name, nhash.id.value, nhash.denom, priority.toBigDecimal())
                        )
                }

                // Save remaining denoms as MSG_BASED_FEE, only if tx is successful
                if (success)
                    tx.tx.authInfo.fee.amountList.filter { it.denom != NHASH }.forEach {
                        val denom = assetService.getAssetRaw(it.denom).second
                        feeList.add(
                            buildInsert(
                                txInfo,
                                MSG_BASED_FEE.name,
                                denom.id.value,
                                denom.denom,
                                it.amount.toBigDecimal()
                            )
                        )
                    }
                feeList
            }

        private fun buildInsert(txInfo: TxData, type: String, markerId: Int, marker: String, amount: BigDecimal) =
            listOf(0, txInfo.blockHeight, 0, txInfo.txHash, type, markerId, marker, amount).toProcedureObject()
    }

    var blockHeight by TxFeeTable.blockHeight
    var txHash by TxFeeTable.txHash
    var txHashId by TxCacheRecord referencedOn TxFeeTable.txHashId
    var feeType by TxFeeTable.feeType
    var markerId by TxFeeTable.markerId
    var marker by TxFeeTable.marker
    var amount by TxFeeTable.amount
}
