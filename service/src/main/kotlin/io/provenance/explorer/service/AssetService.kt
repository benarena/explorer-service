package io.provenance.explorer.service

import com.google.protobuf.Timestamp
import com.google.protobuf.util.JsonFormat
import io.ktor.client.call.receive
import io.ktor.client.features.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.provenance.explorer.KTOR_CLIENT_JAVA
import io.provenance.explorer.config.ExplorerProperties
import io.provenance.explorer.config.ResourceNotFoundException
import io.provenance.explorer.domain.core.logger
import io.provenance.explorer.domain.entities.AssetPricingRecord
import io.provenance.explorer.domain.entities.BaseDenomType
import io.provenance.explorer.domain.entities.MarkerCacheRecord
import io.provenance.explorer.domain.entities.MarkerCacheTable
import io.provenance.explorer.domain.entities.TokenDistributionAmountsRecord
import io.provenance.explorer.domain.entities.TokenDistributionPaginatedResultsRecord
import io.provenance.explorer.domain.entities.TxMarkerJoinRecord
import io.provenance.explorer.domain.extensions.NHASH
import io.provenance.explorer.domain.extensions.pageCountOfResults
import io.provenance.explorer.domain.extensions.toDateTime
import io.provenance.explorer.domain.extensions.toObjectNode
import io.provenance.explorer.domain.extensions.toOffset
import io.provenance.explorer.domain.models.explorer.AssetDetail
import io.provenance.explorer.domain.models.explorer.AssetHolder
import io.provenance.explorer.domain.models.explorer.AssetListed
import io.provenance.explorer.domain.models.explorer.AssetManagement
import io.provenance.explorer.domain.models.explorer.AssetPricing
import io.provenance.explorer.domain.models.explorer.CountStrTotal
import io.provenance.explorer.domain.models.explorer.PagedResults
import io.provenance.explorer.domain.models.explorer.TokenCounts
import io.provenance.explorer.domain.models.explorer.TokenDistribution
import io.provenance.explorer.domain.models.explorer.TokenDistributionAmount
import io.provenance.explorer.domain.models.explorer.toCoinStrWithPrice
import io.provenance.explorer.grpc.extensions.getManagingAccounts
import io.provenance.explorer.grpc.extensions.isMintable
import io.provenance.explorer.grpc.v1.AccountGrpcClient
import io.provenance.explorer.grpc.v1.AttributeGrpcClient
import io.provenance.explorer.grpc.v1.MarkerGrpcClient
import io.provenance.explorer.grpc.v1.MetadataGrpcClient
import io.provenance.marker.v1.MarkerStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.json.JSONArray
import org.json.JSONObject
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class AssetService(
    private val markerClient: MarkerGrpcClient,
    private val attrClient: AttributeGrpcClient,
    private val metadataClient: MetadataGrpcClient,
    private val accountClient: AccountGrpcClient,
    private val protoPrinter: JsonFormat.Printer,
    private val props: ExplorerProperties
) {
    protected val logger = logger(AssetService::class)

    fun getAssets(
        statuses: List<MarkerStatus>,
        page: Int,
        count: Int
    ): PagedResults<AssetListed> {
        val records = MarkerCacheRecord.findByStatusPaginated(statuses, page.toOffset(count), count)
        val pricing = getPricingInfoIn(records.map { it.denom }, "assetList")
        val list = records.map {
            AssetListed(
                it.denom,
                it.markerAddress,
                it.toCoinStrWithPrice(pricing[it.denom]),
                it.status.prettyStatus(),
                it.data?.isMintable() ?: false,
                it.lastTx?.toString(),
                it.markerType.prettyMarkerType()
            )
        }
        val total = MarkerCacheRecord.findCountByStatus(statuses)
        return PagedResults(total.pageCountOfResults(count), list, total)
    }

    fun getAssetRaw(denom: String) = transaction {
        getAssetFromDB(denom) ?: getAndInsertMarker(denom)
    }

    fun getAssetFromDB(denom: String) = transaction {
        MarkerCacheRecord.findByDenom(denom)?.let { Pair(it.id, it) }
    }

    private fun getAndInsertMarker(denom: String) = runBlocking {
        markerClient.getMarkerDetail(denom)?.let {
            MarkerCacheRecord.insertIgnore(
                it.baseAccount.address,
                it.markerType.name,
                it.denom,
                it.status.toString(),
                it,
                getCurrentSupply(denom).toBigDecimal(),
                TxMarkerJoinRecord.findLatestTxByDenom(denom)
            )
        } ?: getCurrentSupply(denom).let {
            val (type, status) = denom.getBaseDenomType()
            MarkerCacheRecord.insertIgnore(
                null,
                type.name,
                denom,
                status.toString(),
                null,
                it.toBigDecimal(),
                TxMarkerJoinRecord.findLatestTxByDenom(denom)
            )
        }
    }

    fun getAssetDetail(denom: String) =
        runBlocking {
            getAssetFromDB(denom)?.let { (id, record) ->
                val txCount = TxMarkerJoinRecord.findCountByDenom(id.value)
                val attributes = async { attrClient.getAllAttributesForAddress(record.markerAddress) }
                val balances = async { accountClient.getAccountBalances(record.markerAddress!!, 1, 1) }
                val price = getPricingInfoIn(listOf(denom), "assetDetail")[denom]
                AssetDetail(
                    record.denom,
                    record.markerAddress,
                    if (record.data != null) AssetManagement(
                        record.data!!.getManagingAccounts(),
                        record.data!!.allowGovernanceControl
                    ) else null,
                    record.toCoinStrWithPrice(price),
                    record.data?.isMintable() ?: false,
                    if (record.markerAddress != null) markerClient.getMarkerHoldersCount(denom).pagination.total.toInt() else 0,
                    txCount,
                    attributes.await().map { attr -> attr.toResponse() },
                    getDenomMetadataSingle(denom).toObjectNode(protoPrinter),
                    TokenCounts(
                        if (record.markerAddress != null) balances.await().pagination.total else 0,
                        if (record.markerAddress != null) metadataClient.getScopesByOwner(record.markerAddress!!).pagination.total.toInt() else 0
                    ),
                    record.status.prettyStatus(),
                    record.markerType.prettyMarkerType()
                )
            } ?: throw ResourceNotFoundException("Invalid asset: $denom")
        }

    fun getAssetHolders(denom: String, page: Int, count: Int) = runBlocking {
        val supply = getCurrentSupply(denom)
        val res = markerClient.getMarkerHolders(denom, page.toOffset(count), count)
        val list = res.balancesList.asFlow().map { bal ->
            val balance = bal.coinsList.first { coin -> coin.denom == denom }.amount
            AssetHolder(bal.address, CountStrTotal(balance, supply, denom))
        }.toList().sortedWith(compareBy { it.balance.count.toBigDecimal() }).asReversed()
        PagedResults(res.pagination.total.pageCountOfResults(count), list, res.pagination.total)
    }

    fun getTokenDistributionStats() = transaction { TokenDistributionAmountsRecord.getStats() }

    fun updateTokenDistributionStats(denom: String) {
        val pageResults = getAssetHoldersWithRetry(denom, 1, 10)
        TokenDistributionPaginatedResultsRecord.savePaginatedResults(pageResults.results)
        if (pageResults.pages > 1) {
            for (i in 2..pageResults.pages) {
                val results = getAssetHoldersWithRetry(denom, i, 10)
                TokenDistributionPaginatedResultsRecord.savePaginatedResults(results.results)
            }
        }
        // calculate ranks
        calculateTokenDistributionStats()
    }

    // 1st requests that take longer than expected result in a
    // DEADLINE_EXCEEDED error. Add retry functionality to give
    // a chance to succeeed.
    private fun getAssetHoldersWithRetry(
        denom: String,
        page: Int,
        count: Int,
        retryCount: Int = 3
    ): PagedResults<AssetHolder> {
        var hasSucceeded = false
        var numberOfTriesRemaining = retryCount
        var assetHolders: PagedResults<AssetHolder>? = null
        while (!hasSucceeded && numberOfTriesRemaining > 0) {
            numberOfTriesRemaining--
            assetHolders = getAssetHolders(denom, page, count)
            hasSucceeded = assetHolders.results.isNotEmpty()
        }

        return assetHolders!!
    }

    private fun calculateTokenDistributionStats() {
        val tokenDistributions = listOf(
            Triple(1, 0, "1"),
            Triple(1, 1, "2"),
            Triple(1, 2, "3"),
            Triple(1, 3, "4"),
            Triple(1, 4, "5"),
            Triple(5, 5, "6-10"),
            Triple(50, 10, "11-50"),
            Triple(50, 50, "51-100"),
            Triple(500, 100, "101-500"),
            Triple(500, 500, "501-1000"),
            Triple("ALL", 1000, "1001-")
        ).map { (limit, offset, range) ->
            val results = TokenDistributionPaginatedResultsRecord.findByLimitOffset(limit, offset)
            val denom = results?.get(0)?.denom!!
            val totalSupply = results[0].total?.toBigDecimal()!!
            val rangeBalance = results.sumOf { it.count.toBigDecimal() }
            val percentOfTotal = rangeBalance.asPercentOf(totalSupply).toPlainString()
            TokenDistribution(range, TokenDistributionAmount(denom, rangeBalance.toString()), percentOfTotal)
        }

        TokenDistributionAmountsRecord.batchUpsert(tokenDistributions)
    }

    fun getMetadata(denom: String?) = getDenomMetadata(denom).map { it.toObjectNode(protoPrinter) }

    // Updates the Marker cache
    fun updateAssets(denoms: Set<String>, txTime: Timestamp) =
        transaction {
            denoms.forEach { marker ->
                runBlocking {
                    val data = markerClient.getMarkerDetail(marker)
                    MarkerCacheRecord.findByDenom(marker)?.apply {
                        if (data != null) this.status = data.status.toString()
                        this.supply = getCurrentSupply(marker).toBigDecimal()
                        this.lastTx = txTime.toDateTime()
                        this.data = data
                    }
                }
            }
        }

    fun getTotalAum() = runBlocking {
        val baseMap = transaction {
            MarkerCacheRecord.find {
                (MarkerCacheTable.status eq MarkerStatus.MARKER_STATUS_ACTIVE.name) and
                    (MarkerCacheTable.supply greater BigDecimal.ZERO)
            }.associate { it.denom to it.supply }
        }
        val pricing = baseMap.keys.toList().chunked(100) { getPricingInfo(it, "totalAUM") }.flatMap { it.toList() }
            .toMap()
            .toMutableMap().also { it.putAll(getPricingForNhash()) }
        baseMap.map { (k, v) -> (pricing[k] ?: BigDecimal.ZERO).multiply(v.toHashSupply(k)) }.sumOf { it }
    }

    fun getAumForList(denoms: Map<String, String>, comingFrom: String): BigDecimal {
        val pricing =
            denoms.keys.toList().chunked(100) { getPricingInfo(it, comingFrom) }.flatMap { it.toList() }.toMap()
                .toMutableMap()
                .also { it.putAll(getPricingForNhash()) }
        return denoms
            .map { (k, v) -> (pricing[k] ?: BigDecimal.ZERO).multiply(v.toBigDecimal().toHashSupply(k)) }
            .sumOf { it }
    }

    fun getPricingInfoIn(denoms: List<String>, comingFrom: String) =
        getPricingInfo(denoms, comingFrom).also { it.putAll(getPricingForNhash()) }

    fun getPricingInfo(denoms: List<String>, comingFrom: String): MutableMap<String, BigDecimal?> = runBlocking {
        if (denoms.isEmpty()) return@runBlocking mutableMapOf<String, BigDecimal?>()
        AssetPricingRecord.findByDenomList(denoms).associate { it.denom to it.pricing.toBigDecimal() }.toMutableMap()
    }

    fun insertAssetPricing(data: AssetPricing) = transaction {
        getAssetRaw(data.markerDenom).first.value.let { AssetPricingRecord.upsert(it, data) }
    }

    fun getPricingAsync(time: String, comingFrom: String) = runBlocking {
        try {
            KTOR_CLIENT_JAVA.get("${props.pricingUrl}/api/v1/pricing/marker/new") {
                parameter("time", time)
            }
        } catch (e: ResponseException) {
            return@runBlocking listOf<AssetPricing>()
                .also { logger.error("Error coming from $comingFrom: ${e.response}") }
        }
    }

    fun getPricingForNhash(): Map<String, BigDecimal> = runBlocking {
        val url = "https://www.dlob.io:443/gecko/external/api/v1/exchange/tickers"
        val res = try {
            KTOR_CLIENT_JAVA.get<HttpResponse>(url)
        } catch (e: ResponseException) {
            return@runBlocking mapOf<String, BigDecimal>().also { logger.error("Error: ${e.response}") }
        }

        if (res.status.value == 200) {
            try {
                JSONArray(res.receive<String>())
                    .first { (it as JSONObject).getString("ticker_id") == "HASH_USD" }
                    .let { mapOf("nhash" to (it as JSONObject).getBigDecimal("last_price")) }
            } catch (e: Exception) {
                mapOf<String, BigDecimal>().also { logger.error("Error: $e") }
            }
        } else mapOf<String, BigDecimal>().also { logger.error("Error reaching Pricing Engine: ${res.status.value}") }
    }

    fun getCurrentSupply(denom: String) = runBlocking { accountClient.getCurrentSupply(denom).amount }

    fun getDenomMetadataSingle(denom: String) = runBlocking { accountClient.getDenomMetadata(denom).metadata }

    fun getDenomMetadata(denom: String?) = runBlocking {
        if (denom != null) listOf(accountClient.getDenomMetadata(denom).metadata)
        else accountClient.getAllDenomMetadata().metadatasList
    }
}

fun BigDecimal.asPercentOf(divisor: BigDecimal): BigDecimal = this.divide(divisor, 20, RoundingMode.CEILING)

fun String.getDenomByAddress() = MarkerCacheRecord.findByAddress(this)?.denom

fun String.prettyStatus() = this.substringAfter("MARKER_STATUS_")
fun String.prettyMarkerType() = if (this.startsWith("MARKER_TYPE")) this.substringAfter("MARKER_TYPE_") else this
fun String.prettyRole() = this.substringAfter("ACCESS_")

fun String.getBaseDenomType() =
    when {
        this.startsWith("ibc/") -> Pair(BaseDenomType.IBC_DENOM, MarkerStatus.MARKER_STATUS_ACTIVE)
        else -> Pair(BaseDenomType.DENOM, MarkerStatus.MARKER_STATUS_UNSPECIFIED)
    }

fun BigDecimal.toHashSupply(denom: String) = if (denom == NHASH) this.divide(1000000000.toBigDecimal()) else this
