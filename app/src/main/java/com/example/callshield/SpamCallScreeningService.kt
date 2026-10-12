
package com.example.callshield

import android.telecom.Call
import android.telecom.CallScreeningService
import android.provider.ContactsContract
import android.provider.CallLog
import android.net.Uri
import android.util.Log
import java.util.concurrent.TimeUnit

class SpamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if(details.callDirection != Call.Details.DIRECTION_INCOMING) return

        val raw = details.handle?.schemeSpecificPart ?: ""
        val norm = normalize(raw)
        if(norm.length < 10){ allow(details); return }

        val prefs = getSharedPreferences("callshield", MODE_PRIVATE)
        val block214 = prefs.getBoolean("block_214", false)
        val allowOutgoing = prefs.getBoolean("allow_outgoing", true)

        // Always allow contacts
        if(isInContacts(raw)){
            allow(details); return
        }

        // Allow recent outgoing (legit callbacks)
        if(allowOutgoing && isInRecentOutgoing(norm)){
            allow(details); return
        }

        // NUCLEAR: Block entire 214
        if(block214 && norm.startsWith("214") || raw.contains("214")){
            // Double check: normalized 10-digit starts with 214 OR raw contains +1 214
            val is214 = norm.startsWith("214") || raw.replace(" ","").contains("214")
            if(is214){
                block(details, "214_area_block $raw")
                return
            }
        }

        // Also block exact 30 numbers from your screenshots if nuclear is off
        val blocked = prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()
        if(blocked.map{ normalize(it) }.contains(norm)){
            block(details, "exact_match_30_list $raw")
            return
        }

        allow(details)
    }

    private fun isInContacts(number: String): Boolean {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val c = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            val found = c?.moveToFirst() == true
            c?.close()
            return found
        } catch(e:Exception){ return false }
    }

    private fun isInRecentOutgoing(norm10: String): Boolean {
        try {
            val cur = contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), "${CallLog.Calls.TYPE}=${CallLog.Calls.OUTGOING_TYPE}", null, "${CallLog.Calls.DATE} DESC LIMIT 200")
            val thirtyDays = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            cur?.use{
                while(it.moveToNext()){
                    val num = it.getString(0) ?: continue
                    val date = it.getLong(1)
                    if(date < thirtyDays) break
                    if(normalize(num) == norm10) return true
                }
            }
        } catch(e:Exception){}
        return false
    }

    private fun normalize(n: String): String {
        val d = n.filter{ it.isDigit() }
        return if(d.length > 10) d.takeLast(10) else d
    }

    private fun allow(d: Call.Details){
        respondToCall(d, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).build())
    }
    private fun block(d: Call.Details, reason: String){
        val prefs = getSharedPreferences("callshield", MODE_PRIVATE)
        prefs.edit().putInt("total_blocked", prefs.getInt("total_blocked",0)+1).putString("last_reason", reason).apply()
        Log.d("CS214", "BLOCK $reason")
        val r = CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false).build()
        respondToCall(d, r)
    }
}
