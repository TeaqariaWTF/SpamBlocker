package spam.blocker.service

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.util.Log
import spam.blocker.R
import spam.blocker.db.ContentRuleTable
import spam.blocker.db.NumberRuleTable
import spam.blocker.db.PatternRule
import spam.blocker.db.QuickCopyRuleTable
import spam.blocker.db.RuleTable
import spam.blocker.def.Def
import spam.blocker.util.Contacts
import spam.blocker.util.Permissions
import spam.blocker.util.SharedPref
import spam.blocker.util.Util

class CheckResult(
    val shouldBlock: Boolean,
    val result: Int,
) {
    var byContactName: String? = null // allowed by contact
    var byFilter: PatternRule? = null // allowed or blocked by this filter rule
    var byRecentApp: String? = null // allowed by recent app
    var stirResult: Int? = null

    // This `reason` will be saved to database
    fun reason(): String {
        if (byContactName != null) return byContactName!!
        if (byFilter != null) return byFilter!!.id.toString()
        if (byRecentApp != null) return byRecentApp!!
        if (stirResult != null) return stirResult.toString()
        return ""
    }

    fun setContactName(name: String): CheckResult {
        byContactName = name
        return this
    }

    fun setFilter(f: PatternRule): CheckResult {
        byFilter = f
        return this
    }

    fun setRecentApp(pkg: String): CheckResult {
        byRecentApp = pkg
        return this
    }
    fun setStirResult(result: Int): CheckResult {
        stirResult = result
        return this
    }
}

interface checker {
    fun priority(): Int
    fun check(): CheckResult?
}

class Checker { // for namespace only

    class Emergency(private val callDetails: Call.Details?) : checker {
        override fun priority(): Int {
            return Int.MAX_VALUE
        }

        override fun check(): CheckResult? {
            if (callDetails == null) // there is no callDetail when testing
                return null

            if (callDetails.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)
                || callDetails.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)) {
                return CheckResult(true, Def.RESULT_ALLOWED_BY_EMERGENCY)
            }

            return null
        }
    }

    class STIR(private val ctx: Context, private val callDetails: Call.Details?) : checker {
        override fun priority(): Int {
            val isExclusive = SharedPref(ctx).isStirExclusive()
            return if (isExclusive) 0 else 10
        }

        override fun check(): CheckResult? {
            // STIR only works >= Android 11
            if (Build.VERSION.SDK_INT < 30) {
                return null
            }

            // there is no callDetail when testing
            if (callDetails == null)
                return null

            val spf = SharedPref(ctx)
            if (!spf.isStirEnabled())
                return null

            val exclusive = spf.isStirExclusive()
            val includeUnverified = spf.isStirIncludeUnverified()

            val stir = callDetails.callerNumberVerificationStatus

            val pass = stir == Connection.VERIFICATION_STATUS_PASSED
            val unverified = stir == Connection.VERIFICATION_STATUS_NOT_VERIFIED
            val fail = stir == Connection.VERIFICATION_STATUS_FAILED

            Log.d(Def.TAG, "STIR: pass: $pass, unverified: $unverified, fail: $fail, exclusive: $exclusive")

            if (exclusive) {
                if (fail || (includeUnverified && unverified)) {
                    return CheckResult(true, Def.RESULT_BLOCKED_BY_STIR)
                        .setStirResult(stir)
                }
            } else {
                if (pass || (includeUnverified && unverified)) {
                    return CheckResult(false, Def.RESULT_ALLOWED_BY_STIR)
                        .setStirResult(stir)
                }
            }

            return null
        }
    }

    class Contact(private val ctx: Context, private val rawNumber: String) : checker {
        override fun priority(): Int {
            val isExclusive = SharedPref(ctx).isContactExclusive()
            return if (isExclusive) 0 else 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)

            if (!spf.isContactEnabled() or !Permissions.isContactsPermissionGranted(ctx)) {
                return null
            }
            val contact = Contacts.findByRawNumberAuto(ctx, rawNumber)
            if (contact != null) {
                Log.i(Def.TAG, "is contact")
                return CheckResult(false, Def.RESULT_ALLOWED_BY_CONTACT)
                    .setContactName(contact.name)
            } else {
                if (spf.isContactExclusive()) {
                    return CheckResult(true, Def.RESULT_BLOCKED_BY_NON_CONTACT)
                }
            }
            return null
        }
    }

    class RepeatedCall(private val ctx: Context, private val rawNumber: String) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)
            if (!spf.isRepeatedCallEnabled()
                or !Permissions.isCallLogPermissionGranted(ctx) )
            {
                return null
            }
            val (times, durationMinutes) = spf.getRepeatedConfig()

            val durationMillis = durationMinutes.toLong() * 60 * 1000

            // repeated count of call/sms, sms also counts
            val nCalls = Permissions.countHistoryCallByNumber(ctx, rawNumber, Def.DIRECTION_INCOMING, durationMillis)
            val nSMSs = Permissions.countHistorySMSByNumber(ctx, rawNumber, Def.DIRECTION_INCOMING, durationMillis)
            if (nCalls + nSMSs >= times) {
                return CheckResult(false, Def.RESULT_ALLOWED_BY_REPEATED)
            }
            return null
        }
    }
    class Dialed(private val ctx: Context, private val rawNumber: String) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)
            if (!spf.isDialedEnabled()
                or !Permissions.isCallLogPermissionGranted(ctx) )
            {
                return null
            }
            val durationDays = spf.getDialedConfig()

            val durationMillis = durationDays.toLong() * 24 * 3600 * 1000

            // repeated count of call/sms, sms also counts
            val nCalls = Permissions.countHistoryCallByNumber(ctx, rawNumber, Def.DIRECTION_OUTGOING, durationMillis)
            val nSMSs = Permissions.countHistorySMSByNumber(ctx, rawNumber, Def.DIRECTION_OUTGOING, durationMillis)
            if (nCalls + nSMSs > 0) {
                return CheckResult(false, Def.RESULT_ALLOWED_BY_DIALED)
            }
            return null
        }
    }

    class OffTime(private val ctx: Context) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)
            if (!spf.isOffTimeEnabled()) {
                return null
            }
            val (stHour, stMin) = spf.getOffTimeStart()
            val (etHour, etMin) = spf.getOffTimeEnd()

            if (Util.isCurrentTimeWithinRange(stHour, stMin, etHour, etMin)) {
                return CheckResult(false, Def.RESULT_ALLOWED_BY_OFF_TIME)
            }

            return null
        }
    }

    class RecentApp(private val ctx: Context) : checker {
        override fun priority(): Int {
            return 10
        }

        override fun check(): CheckResult? {
            val spf = SharedPref(ctx)

            val enabledPackages = spf.getRecentAppList()
            if (enabledPackages.isEmpty()) {
                return null
            }
            val inXmin = spf.getRecentAppConfig()
            val usedApps = Permissions.listUsedAppWithinXSecond(ctx, inXmin * 60)

            val intersection = enabledPackages.intersect(usedApps.toSet())
            Log.d(
                Def.TAG,
                "--- enabled: $enabledPackages, used: $usedApps, intersection: $intersection"
            )

            if (intersection.isNotEmpty()) {
                return CheckResult(
                    false,
                    Def.RESULT_ALLOWED_BY_RECENT_APP
                ).setRecentApp(intersection.first())
            }
            return null
        }
    }

    /*
        Check if a number rule matches the incoming number
     */
    class Number(private val rawNumber: String, private val filter: PatternRule) : checker {
        override fun priority(): Int {
            return filter.priority
        }

        override fun check(): CheckResult? {
            val opts = Util.flagsToRegexOptions(filter.patternFlags)
            if (filter.pattern.toRegex(opts).matches(Util.clearNumber(rawNumber))) {
                val block = filter.isBlacklist
                return CheckResult(
                    block,
                    if (block) Def.RESULT_BLOCKED_BY_NUMBER else Def.RESULT_ALLOWED_BY_NUMBER
                ).setFilter(filter)
            }

            return null
        }
    }
    /*
        Check if text message body matches the SMS Content rule,
        the number is also checked when "for particular number" is enabled
     */
    class Content(private val rawNumber: String, private val messageBody: String, private val filter: PatternRule) : checker {
        override fun priority(): Int {
            return filter.priority
        }

        override fun check(): CheckResult? {
            val f = filter // for short

            val opts = Util.flagsToRegexOptions(filter.patternFlags)
            val optsExtra = Util.flagsToRegexOptions(filter.patternExtraFlags)

            val contentMatches = f.pattern.toRegex(opts).matches(messageBody)
            val particularNumberMatches = f.patternExtra.toRegex(optsExtra).matches(Util.clearNumber(rawNumber))

            val matches = if (filter.patternExtra != "") { // for particular number enabled
                contentMatches && particularNumberMatches
            } else {
                contentMatches
            }

            if (matches) {
                Log.d(Def.TAG, "filter matches: $f")

                val block = f.isBlacklist

                return CheckResult(
                    block,
                    if (block) Def.RESULT_BLOCKED_BY_CONTENT else Def.RESULT_ALLOWED_BY_CONTENT
                ).setFilter(f)
            }
            return null
        }
    }

    companion object {

        fun checkCall(ctx: Context, rawNumber: String, callDetails: Call.Details? = null): CheckResult {
            val checkers = arrayListOf(
                Checker.Emergency(callDetails),
                Checker.STIR(ctx, callDetails),
                Checker.Contact(ctx, rawNumber),
                Checker.RepeatedCall(ctx, rawNumber),
                Checker.Dialed(ctx, rawNumber),
                Checker.RecentApp(ctx),
                Checker.OffTime(ctx)
            )

            //  add number rules to checkers
            val filters = NumberRuleTable().listRules(ctx, Def.FLAG_FOR_CALL)
            checkers += filters.map {
                Checker.Number(rawNumber, it)
            }

            // sort by priority desc
            checkers.sortByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: CheckResult? = null
            checkers.firstOrNull {
                result = it.check()
                result != null
            }
            // match found
            if (result != null) {
                return result!!
            }

            // pass by default
            return CheckResult(false, Def.RESULT_ALLOWED_BY_DEFAULT)
        }

        fun checkSms(
            ctx: Context,
            rawNumber: String,
            messageBody: String
        ): CheckResult {

            val checkers = arrayListOf<checker>(
                Checker.Contact(ctx, rawNumber),
                Checker.OffTime(ctx)
            )

            //  add number rules to checkers
            val numberFilters = NumberRuleTable().listRules(ctx, Def.FLAG_FOR_SMS)
            checkers += numberFilters.map {
                Checker.Number(rawNumber, it)
            }

            //  add sms content rules to checkers
            val contentFilters = ContentRuleTable().listRules(ctx, 0/* doesn't care */)
            checkers += contentFilters.map {
                Checker.Content(rawNumber, messageBody, it)
            }

            // sort by priority desc
            checkers.sortByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: CheckResult? = null
            checkers.firstOrNull {
                result = it.check()
                result != null
            }
            // match found
            if (result != null) {
                return result!!
            }

            // pass by default
            return CheckResult(false, Def.RESULT_ALLOWED_BY_DEFAULT)
        }

        fun checkQuickCopy(ctx: Context, messageBody: String) : Pair<PatternRule, String>? {

            val rules = QuickCopyRuleTable().listRules(ctx, Def.FLAG_FOR_SMS)

            var result : MatchResult? = null

            val rule = rules.firstOrNull{
                val opts = Util.flagsToRegexOptions(it.patternFlags)

                result = it.pattern.toRegex(opts).find(messageBody)
                result != null
            }

            return if (rule == null)
                null
            else {
                /*
                    lookbehind: has `value`, no `group(1)`
                    capturing group: has both, should use group(1) only

                    so the logic is:
                        if has `value` && no `group(1)`
                            use `value`
                        else if has both
                            use `group1`
                 */

                val v = result?.value
                val g1 = result?.groupValues?.getOrNull(1)

                if (v != null && g1 == null) {
                    return Pair(rule, v)
                } else if (v != null && g1 != null) {
                    return Pair(rule, g1)
                }

                return null
            }
        }


        fun reasonStr(ctx: Context, filterTable: RuleTable?, reason: String) : String {
            val f = filterTable?.findPatternRuleById(ctx, reason.toLong())

            val reasonStr = if (f != null) {
                if (f.description != "") f.description else f.patternStr()
            } else {
                ctx.resources.getString(R.string.deleted_filter)
            }
            return reasonStr
        }
        fun resultStr(ctx: Context, result: Int, reason: String): String {

            fun s(id: Int) : String {
                return ctx.resources.getString(id)
            }

            return when (result) {
                Def.RESULT_ALLOWED_BY_CONTACT ->  s(R.string.contact)
                Def.RESULT_BLOCKED_BY_NON_CONTACT ->  s(R.string.non_contact)
                Def.RESULT_ALLOWED_BY_STIR, Def.RESULT_BLOCKED_BY_STIR -> {
                    when (reason.toInt()) {
                        Connection.VERIFICATION_STATUS_NOT_VERIFIED -> "${s(R.string.stir)} ${s(R.string.unverified)}"
                        Connection.VERIFICATION_STATUS_PASSED -> "${s(R.string.stir)} ${s(R.string.valid)}"
                        Connection.VERIFICATION_STATUS_FAILED -> "${s(R.string.stir)} ${s(R.string.spoof)}"
                        else -> s(R.string.stir)
                    }
                }
                Def.RESULT_ALLOWED_BY_EMERGENCY ->  ctx.resources.getString(R.string.emergency_call)
                Def.RESULT_ALLOWED_BY_RECENT_APP ->  ctx.resources.getString(R.string.recent_app) + ": "
                Def.RESULT_ALLOWED_BY_REPEATED ->  ctx.resources.getString(R.string.repeated_call)
                Def.RESULT_ALLOWED_BY_DIALED ->  ctx.resources.getString(R.string.dialed)
                Def.RESULT_ALLOWED_BY_OFF_TIME ->  ctx.resources.getString(R.string.off_time)
                Def.RESULT_ALLOWED_BY_NUMBER ->  ctx.resources.getString(R.string.whitelist) + ": " + reasonStr(
                    ctx, NumberRuleTable(), reason)
                Def.RESULT_BLOCKED_BY_NUMBER ->  ctx.resources.getString(R.string.blacklist) + ": " + reasonStr(
                    ctx, NumberRuleTable(), reason)
                Def.RESULT_ALLOWED_BY_CONTENT ->  ctx.resources.getString(R.string.content) + ": " + reasonStr(
                    ctx, ContentRuleTable(), reason)
                Def.RESULT_BLOCKED_BY_CONTENT ->  ctx.resources.getString(R.string.content) + ": " + reasonStr(
                    ctx, ContentRuleTable(), reason)

                else -> ctx.resources.getString(R.string.passed_by_default)
            }
        }
    }
}
