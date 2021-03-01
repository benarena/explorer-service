package io.provenance.explorer.service

import io.provenance.explorer.domain.entities.AccountRecord
import io.provenance.explorer.domain.models.explorer.AccountDetail
import io.provenance.explorer.domain.models.explorer.toData
import io.provenance.explorer.grpc.toKeyValue
import io.provenance.explorer.grpc.v1.AccountGrpcClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

@Service
class AccountService(private val accountClient: AccountGrpcClient) {

    private fun getAccountRaw(address: String) = transaction {
        AccountRecord.findById(address)
    } ?: accountClient.getAccountInfo(address).let { AccountRecord.insertIgnore(it)!! }

    fun getAccountDetail(address: String) = getAccountRaw(address).let {
        AccountDetail(
            it.type,
            it.id.value,
            it.accountNumber,
            it.baseAccount.sequence.toInt(),
            it.baseAccount.pubKey.toKeyValue(),
            getAccountBalances(address)
        )
    }

    private fun getAccountBalances(address: String) = accountClient.getAccountBalances(address).map { it.toData()}

    fun getTotalSupply(denom: String) = accountClient.getSupplyByDenom(denom).amount.amount.toBigDecimal()

}

fun String.getAccountType() = this.split(".").last()
