package io.provenance.explorer.service

import io.provenance.explorer.domain.entities.MarkerCacheRecord
import io.provenance.explorer.domain.models.explorer.AssetDetail
import io.provenance.explorer.domain.models.explorer.AssetHolder
import io.provenance.explorer.domain.models.explorer.AssetListed
import io.provenance.explorer.grpc.getManagingAccounts
import io.provenance.explorer.grpc.isMintable
import io.provenance.explorer.grpc.v1.MarkerGrpcClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.math.RoundingMode

@Service
class AssetService(private val pbClient: MarkerGrpcClient, private val accountService: AccountService) {

    fun getAllAssets() = pbClient.getAllMarkers().map {
        MarkerCacheRecord.insertIgnore(it).let { detail ->
            AssetListed(
                detail.denom,
                detail.baseAccount.address,
                accountService.getTotalSupply(detail.denom),
                detail.supply.toBigDecimal()
            )
        }
    }

    private fun getAssetRaw(denom: String) = transaction {
        MarkerCacheRecord.findById(denom)?.data ?:
            pbClient.getMarkerDetail(denom).let { MarkerCacheRecord.insertIgnore(it) }
    }

    fun getAssetDetail(denom: String) =
        (getAssetRaw(denom))
            .let {
                AssetDetail(
                    it.denom,
                    it.baseAccount.address,
                    it.getManagingAccounts(),
                    accountService.getTotalSupply(denom),
                    it.supply.toBigDecimal(),
                    it.isMintable(),
                    getAssetHolders(denom).count(),
                    null // TODO: Figure out how to count txns for this asset
                )
            }

    fun getAssetHolders(denom: String) = accountService.getTotalSupply(denom).let { supply ->
        pbClient.getMarkerHolders(denom).balancesList
            .map { bal ->
                val balance = bal.coinsList.first { coin -> coin.denom == denom }.amount.toBigDecimal()
                AssetHolder(bal.address, balance, balance.divide(supply, 6, RoundingMode.HALF_UP))
            }
    }
}
