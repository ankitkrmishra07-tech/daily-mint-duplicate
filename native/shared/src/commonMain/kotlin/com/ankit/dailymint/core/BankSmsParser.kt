package com.ankit.dailymint.core

data class ParsedSms(
    val parsed: Boolean,
    val paise: Long? = null,
    val direction: String? = null,
    val from: String? = null,
    val to: String? = null,
    val bank: String? = null,
    val referenceId: String? = null,
    val confidence: Int = 0,
    val reason: String? = null
)

object BankSmsParser {
    private val money = Regex("""(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:INR|Rs\.?)""", RegexOption.IGNORE_CASE)
    private val bare = Regex("""\b(?:debited|credited|deducted|sent|spent|paid|transferred|deposited)\s+(?:by|for|with|from)?\s*([\d,]+(?:\.\d{1,2})?)\b""", RegexOption.IGNORE_CASE)
    private data class Candidate(val paise: Long, val start: Int, val end: Int, val bare: Boolean)
    fun parse(rawSms: String, sender: String = "", minimumConfidence: Int = 50): ParsedSms {
        val text = rawSms.replace("\u00a0", " ").replace(Regex("""\s+"""), " ").trim()
        val skips = listOf("otp" to ParserData.otpSkipRules, "future_debit" to ParserData.futureDebitSkipRules,
            "card_payment_ack" to ParserData.cardPaymentAckSkipRules, "reward_offer" to ParserData.rewardOfferSkipRules)
        skips.firstOrNull { (_, rules) -> rules.any { it.containsMatchIn(text) } }?.let { return ParsedSms(false, reason = it.first) }
        if ((ParserData.debitKeywordRules + ParserData.creditKeywordRules).none { it.containsMatchIn(text) })
            return ParsedSms(false, reason = "no_transaction_keyword")
        val allCandidates = listOf(money to false, bare to true).flatMap { (rule, isBare) ->
            rule.findAll(text).mapNotNull { match ->
                val group = match.groups[1]?.takeIf { it.value.isNotEmpty() } ?: match.groups[2] ?: return@mapNotNull null
                Money.parse(group.value)?.let { Candidate(it, group.range.first, group.range.last + 1, isBare) }
            }.toList()
        }
        if (allCandidates.isEmpty()) return ParsedSms(false, reason = "no_amount")
        val candidates = allCandidates.filter { candidate ->
            val context = text.substring((candidate.start - 35).coerceAtLeast(0), (candidate.end + 15).coerceAtMost(text.length))
            ParserData.balanceRejectRules.none { it.containsMatchIn(context) }
        }
        if (candidates.isEmpty()) return ParsedSms(false, reason = "balance_only")
        val bank = resolveBank(text, sender)
        val parsed = candidates.mapNotNull { candidate ->
            val start = (candidate.start - 90).coerceAtLeast(0)
            val context = text.substring(start, (candidate.end + 120).coerceAtMost(text.length))
            val offset = candidate.start - start
            fun distance(rules: List<Regex>): Int = rules.flatMap { it.findAll(context).map { match -> kotlin.math.abs(match.range.first - offset) }.toList() }.minOrNull() ?: 1000000
            val debit = distance(ParserData.debitKeywordRules)
            val credit = distance(ParserData.creditKeywordRules)
            if (minOf(debit, credit) == 1000000) return@mapNotNull null
            val direction = if (debit <= credit) "debit" else "credit"
            val account = firstEntity(text, ParserData.accountRules)
            val counterparty = firstEntity(text, if (direction == "credit") ParserData.creditFromRules else ParserData.debitToRules)
            val from = if (direction == "credit") counterparty else account
            val to = if (direction == "credit") account else counterparty
            var confidence = 30 + (if (minOf(debit, credit) <= 20) 36 else 24)
            if (bank != null) confidence += 16
            if (from != null) confidence += 9
            if (to != null) confidence += 9
            if (candidate.bare) confidence -= 4
            if (debit != 1000000 && credit != 1000000 && kotlin.math.abs(debit - credit) < 12) confidence -= 10
            ParsedSms(true, candidate.paise, direction, from, to, bank, reference(text), confidence.coerceIn(0, 100))
        }.maxByOrNull { it.confidence }
        return parsed?.takeIf { it.confidence >= minimumConfidence } ?: ParsedSms(false, reason = "low_confidence")
    }
    fun resolveBank(text: String, sender: String = ""): String? {
        ParserData.banks.firstOrNull { (_, rules) -> rules.any { it.containsMatchIn(sender) } }?.let { return it.first }
        return ParserData.banks.flatMap { (name, rules) -> rules.mapNotNull { it.find(text)?.let { match -> name to match.range.first } } }
            .minByOrNull { it.second }?.first
    }
    private fun reference(text: String): String? = listOf(
        """\bUPI\s+Ref(?:\s+(?:ID|No))?\s*[:#-]?\s*([A-Z0-9]{8,40})\b""",
        """\bRef\s*[:#-]\s*([A-Z][A-Z0-9]{8,40})\b""",
        """\bRRN\s*[:#-]?\s*(\d{12,13})\b""",
        """\b(?:UPI\s+Ref\s+ID|UPI\s+Ref\s+No|Ref|Refno|Ref\s+No|UPI)\s*[:#-]?\s*(\d{12})\b""",
        """\b(\d{12})\b"""
    ).firstNotNullOfOrNull { Regex(it, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) }
    private fun firstEntity(text: String, rules: List<Regex>): String? =
        rules.firstNotNullOfOrNull { rule -> rule.find(text)?.groupValues?.getOrNull(1)?.let(::cleanEntity) }
    private fun cleanEntity(value: String): String? {
        val cleaned = value.replace(Regex("""\s+"""), " ")
            .replace(Regex("""^(?:deposited\s+in|spent\s+using|debited\s+from|credited\s+to)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Dear\s+UPI\s+user\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^your\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[.;]+$"""), "").trim()
        if (cleaned.isEmpty() || Regex("""^UPI(?!/)|^account$|^\d{8,}$|^[A-Z][A-Za-z&.]+\s+(?:A/C|Account)$|^(?:report(?: an)? issue|report fraud|block|call)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) return null
        Regex("""^(?:a/c|acct|account)\s+no\.?\s*[Xx*.]*(\d{3,4})$""", RegexOption.IGNORE_CASE).find(cleaned)?.let { return "A/c " + it.groupValues[1] }
        return cleaned
    }
}

object MerchantTagger {
    fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    fun cleanName(value: String): String = value.substringBefore('@').trim()
    fun learningKey(value: String): String = normalize(cleanName(value).substringBefore('.'))
    fun category(name: String, income: Boolean, learned: Map<String, String>, available: List<String>): String {
        val normalized = normalize(cleanName(name))
        // Credits must remain credits even if a merchant matches an expense keyword.
        if (income) {
            if (listOf("refund", "reimbursement", "repaid", "repayment", "settlement", "reversal")
                    .any { normalized.contains(it) }) return "Settlement"
            if (listOf("own account", "own a c", "self transfer")
                    .any { normalized.contains(it) }) return "Own account transfer"
            return "Income"
        }
        val static = ParserData.tags.firstOrNull { (category, words) ->
            category in available && words.any {
                val key = normalize(it)
                if (key.length <= 3) key in normalized.split(' ') else normalized.contains(key)
            }
        }?.first
        if (static != null) return static
        return learned.entries.firstOrNull { (key, category) ->
            normalized.isNotEmpty() && key.isNotEmpty() && category in available &&
                (normalized.contains(key) || key.contains(normalized))
        }?.value ?: "Miscellaneous"
    }
}
