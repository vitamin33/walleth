package org.walleth.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_relay.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.json.JSONObject
import org.kethereum.eip155.extractChainID
import org.kethereum.erc681.ERC681
import org.kethereum.erc681.generateURL
import org.kethereum.functions.rlp.RLPList
import org.kethereum.functions.rlp.decodeRLP
import org.kethereum.functions.rlp.encode
import org.kethereum.functions.toTransaction
import org.kethereum.functions.toTransactionSignatureData
import org.kethereum.keccakshortcut.keccak
import org.kethereum.model.ChainDefinition
import org.kethereum.model.SignatureData
import org.kethereum.model.Transaction
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.android.closestKodein
import org.kodein.di.generic.instance
import org.ligi.kaxt.startActivityFromClass
import org.ligi.kaxtui.alert
import org.walleth.R
import org.walleth.activities.qrscan.startScanActivityForResult
import org.walleth.data.AppDatabase
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.transactions.TransactionState
import org.walleth.data.transactions.toEntity
import org.walleth.functions.toHexString
import org.walleth.khex.clean0xPrefix
import org.walleth.khex.hexToByteArray
import org.walleth.khex.toHexString
import org.walleth.util.isParityUnsignedTransactionJSON
import org.walleth.util.isSignedTransactionJSON
import org.walleth.util.isUnsignedTransactionJSON
import java.math.BigInteger

private const val KEY_CONTENT = "KEY_OFFLINE_TX_CONTENT"


fun Context.getOfflineTransactionIntent(content: String) = Intent(this, OfflineTransactionActivity::class.java).apply {
    putExtra(KEY_CONTENT, content)
}

class OfflineTransactionActivity : AppCompatActivity(), KodeinAware {

    override val kodein: Kodein by closestKodein()

    private val networkDefinitionProvider: NetworkDefinitionProvider by instance()
    private val appDatabase: AppDatabase by instance()
    private val currentAddressProvider: CurrentAddressProvider by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_relay)

        supportActionBar?.subtitle = getString(R.string.relay_transaction)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fab.setOnClickListener {
            execute()
        }

        parity_signer_button.setOnClickListener {
            startActivityFromClass(ParitySignerQRActivity::class.java)
        }

        intent.getStringExtra(KEY_CONTENT)?.let {
            if (!it.isEmpty()) {
                transaction_to_relay_hex.setText(it)
                execute()
            }
        }

    }

    private fun execute() {
        val content = transaction_to_relay_hex.text.toString()
        when {
            content.isUnsignedTransactionJSON() -> handleUnsignedTransactionJson(content)
            content.isParityUnsignedTransactionJSON() -> handleParityUnsignedTransactionJson(content)
            content.isSignedTransactionJSON() -> {
                val json = JSONObject(content)

                try {
                    val transactionRLP = json.getString("signedTransactionRLP").hexToByteArray()
                    val gethTransaction = transactionRLP.decodeRLP()
                    alert(" gethTransaction " + (gethTransaction is RLPList))
                    /*
                val signatureData = gethTransaction.extractSignatureData()

                if (signatureData == null) {
                    alert("Found unsigned TX - but must be signed")
                } else {
                    val extractChainID = signatureData.extractChainID()
                    val chainId = if (extractChainID == null) {
                        BigInt(networkDefinitionProvider.getCurrent().chain.id)
                    } else {
                        BigInt(extractChainID.toLong())
                    }
                    val transaction = createTransactionWithDefaults(
                            value = BigInteger(gethTransaction.value.toString()),
                            from = gethTransaction.getFrom(chainId).toKethereumAddress(),
                            to = gethTransaction.to!!.toKethereumAddress(),
                            chain = ChainDefinition(chainId.toBigInteger().toLong()),
                            nonce = BigInteger(gethTransaction.nonce.toString()),
                            creationEpochSecond = System.currentTimeMillis() / 1000,
                            txHash = gethTransaction.hash.hex
                    )
                    createTransaction(transaction, signatureData)
                }
                */
                } catch (e: Exception) {
                    alert(getString(R.string.input_not_valid_message, e.message), getString(R.string.input_not_valid_title))
                }

            }
            else -> executeForRLP()
        }

    }

    private fun handleParityUnsignedTransactionJson(content: String) {
        val json = JSONObject(content)

        val dataJSON = json.getJSONObject("data")
        val rlp = dataJSON.getString("rlp").hexToByteArray().decodeRLP()
        if (rlp is RLPList) {
            val transaction = rlp.toTransaction()
            if (transaction == null) {
                alert("could not decode transaction")
            } else {
                handleUnsignedTransaction(
                        from = "0x" + dataJSON.getString("account").clean0xPrefix(),
                        to = transaction.to!!.hex,
                        data = transaction.input.toHexString(),
                        value = transaction.value.toHexString(),
                        nonce = transaction.nonce!!.toHexString(),
                        gasPrice = transaction.gasPrice.toHexString(),
                        gasLimit = transaction.gasLimit.toHexString(),
                        chainId = networkDefinitionProvider.getCurrent().chain.id,
                        parityFlow = true
                )
            }
        } else {
            alert("Invalid RLP")
        }
    }

    private fun handleUnsignedTransactionJson(content: String) {
        val json = JSONObject(content)
        handleUnsignedTransaction(
                from = json.getString("from"),
                chainId = json.getLong("chainId"),
                to = json.getString("to"),
                gasLimit = json.getString("gasLimit"),
                value = json.getString("value"),
                nonce = json.getString("nonce"),
                data = json.getString("data"),
                gasPrice = json.getString("gasPrice"),
                parityFlow = false
        )
    }

    private fun handleUnsignedTransaction(from: String,
                                          chainId: Long,
                                          to: String,
                                          value: String,
                                          gasLimit: String,
                                          nonce: String,
                                          data: String,
                                          gasPrice: String,
                                          parityFlow: Boolean) {

        val currentAccount = currentAddressProvider.getCurrent().hex
        if (from.clean0xPrefix().toLowerCase() != currentAccount.clean0xPrefix().toLowerCase()) {
            alert("The from field of the transaction ($from) does not match your current account ($currentAccount)")
            return
        }

        if (chainId != networkDefinitionProvider.getCurrent().chain.id) {
            alert("The chainId of the transaction ($chainId) does not match your current chainId")
            return
        }

        val url = ERC681(scheme = "ethereum",
                address = to,
                value = BigInteger(value.clean0xPrefix(), 16),
                gas = BigInteger(gasLimit.clean0xPrefix(), 16),
                chainId = chainId
        ).generateURL()

        startActivity(Intent(this, CreateTransactionActivity::class.java).apply {
            setData(Uri.parse(url))
            putExtra("nonce", nonce)
            putExtra("data", data)
            putExtra("gasPrice", gasPrice)
            putExtra("from", from)
            putExtra("parityFlow", parityFlow)
        })
    }

    private fun executeForRLP() {

        try {
            val transactionRLP = transaction_to_relay_hex.text.toString().hexToByteArray()

            val rlp = transactionRLP.decodeRLP()

            val rlpList = rlp as RLPList

            if (rlpList.element.size != 9) {
                alert("Found RLP without signature - this is not supported anymore - the transaction source must be in JSON and include the chainID")
            } else {

                val signatureData = rlpList.toTransactionSignatureData()
                val transaction = rlpList.toTransaction()?.apply {
                    txHash = rlpList.encode().keccak().toHexString()
                }

                ERC681(address = transaction?.to?.hex)


                val extractChainID = signatureData.extractChainID()
                val chainId = extractChainID?.toLong() ?: networkDefinitionProvider.getCurrent().chain.id

                transaction?.chain = ChainDefinition(chainId)
                transaction?.let {
                    createTransaction(it, signatureData)
                }
            }

        } catch (e: Exception) {
            alert(getString(R.string.input_not_valid_message, e.message), getString(R.string.input_not_valid_title))
        }
    }

    private fun createTransaction(transaction: Transaction, signatureData: SignatureData?) {
        async(UI) {
            try {

                async(CommonPool) {

                    val transactionState = TransactionState(needsSigningConfirmation = signatureData == null)

                    appDatabase.transactions.upsert(transaction.toEntity(signatureData, transactionState))

                }.await()

                startActivity(getTransactionActivityIntentForHash(transaction.txHash!!))
                finish()

            } catch (e: Exception) {
                alert("Problem " + e.message)
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_offline_transaction, menu)
        return super.onCreateOptionsMenu(menu)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {


        resultData?.let {
            if (it.hasExtra("SCAN_RESULT")) {
                transaction_to_relay_hex.setText(it.getStringExtra("SCAN_RESULT"))
            }
        }


    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {

        R.id.menu_scan -> true.also {
            startScanActivityForResult(this)
        }

        android.R.id.home -> true.also {
            finish()
        }

        else -> super.onOptionsItemSelected(item)
    }
}
