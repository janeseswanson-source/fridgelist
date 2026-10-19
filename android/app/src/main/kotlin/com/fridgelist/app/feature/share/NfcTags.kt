package com.fridgelist.app.feature.share

import android.app.Activity
import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.util.Log
import java.io.IOException

enum class NfcWriteResult { OK, TOO_SMALL, READ_ONLY, UNSUPPORTED, FAILED }

/**
 * Writes the share link as a single NDEF URI record (ARCHITECTURE §8.7). No Android Application
 * Record, so phones without the app open the guest page; never made read-only, so a tag can be
 * rewritten when a link is revoked.
 */
object NfcTags {
    fun message(url: String): NdefMessage = NdefMessage(NdefRecord.createUri(url))

    /** NTAG213 holds 137 bytes of NDEF data, 215 holds 496, 216 holds 868. */
    fun fits(url: String, maxSize: Int): Boolean = message(url).byteArrayLength <= maxSize

    fun write(tag: Tag, url: String): NfcWriteResult {
        val message = message(url)
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> NfcWriteResult.READ_ONLY
                    ndef.maxSize < message.byteArrayLength -> NfcWriteResult.TOO_SMALL
                    else -> {
                        ndef.writeNdefMessage(message)
                        NfcWriteResult.OK
                    }
                }
            } catch (e: Exception) {
                failure(e)
            } finally {
                runCatching { ndef.close() }
            }
        }
        // A blank sticker that was never formatted for NDEF.
        NdefFormatable.get(tag)?.let { formatable ->
            return try {
                formatable.connect()
                formatable.format(message)
                NfcWriteResult.OK
            } catch (e: Exception) {
                failure(e)
            } finally {
                runCatching { formatable.close() }
            }
        }
        return NfcWriteResult.UNSUPPORTED
    }

    private fun failure(e: Exception): NfcWriteResult {
        Log.i(TAG, "write failed: ${e::class.simpleName}")
        return when (e) {
            is TagLostException, is IOException -> NfcWriteResult.FAILED
            is FormatException -> NfcWriteResult.UNSUPPORTED
            else -> NfcWriteResult.FAILED
        }
    }

    fun adapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    /** Reader mode while the "hold the sticker" screen shows: tags come here, not to other apps. */
    fun enableReader(activity: Activity, onTag: (Tag) -> Unit) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter(activity)?.enableReaderMode(activity, onTag, flags, Bundle())
    }

    fun disableReader(activity: Activity) {
        runCatching { adapter(activity)?.disableReaderMode(activity) }
    }

    private const val TAG = "NfcTags"
}
