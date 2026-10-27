package jp.daizo.morse.core

/**
 * 和文モールス符号の表。PC のアプリ(morse-ai/morse_table.py と light-morse-ai/light_table.py)と同じ 65 文字。
 *
 * 短点は '.'、長点は '-'。濁音・半濁音は「基本の仮名 + 濁点(..)/半濁点(..--.)」の 2 つの符号で送る。
 * 区切り「」」は句点「。」と同じ符号(.-.-..)なので、受信では「。」として読む。
 */
object MorseTable {
    val CODES: Map<Char, String> = linkedMapOf(
        'イ' to ".-", 'ロ' to ".-.-", 'ハ' to "-...", 'ニ' to "-.-.", 'ホ' to "-..", 'ヘ' to ".", 'ト' to "..-..",
        'チ' to "..-.", 'リ' to "--.", 'ヌ' to "....", 'ル' to "-.--.", 'ヲ' to ".---", 'ワ' to "-.-", 'カ' to ".-..",
        'ヨ' to "--", 'タ' to "-.", 'レ' to "---", 'ソ' to "---.", 'ツ' to ".--.", 'ネ' to "--.-", 'ナ' to ".-.",
        'ラ' to "...", 'ム' to "-", 'ウ' to "..-", 'ヰ' to ".-..-", 'ノ' to "..--", 'オ' to ".-...", 'ク' to "...-",
        'ヤ' to ".--", 'マ' to "-..-", 'ケ' to "-.--", 'フ' to "--..", 'コ' to "----", 'エ' to "-.---", 'テ' to ".-.--",
        'ア' to "--.--", 'サ' to "-.-.-", 'キ' to "-.-..", 'ユ' to "-..--", 'メ' to "-...-", 'ミ' to "..-.-",
        'シ' to "--.-.", 'ヱ' to ".--..", 'ヒ' to "--..-", 'モ' to "-..-.", 'セ' to ".---.", 'ス' to "---.-", 'ン' to ".-.-.",
        '゛' to "..", '゜' to "..--.", 'ー' to ".--.-", '、' to ".-.-.-", '。' to ".-.-..",
        '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....",
        '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.", '0' to "-----",
        '（' to "-.--.-", '）' to ".-..-.",
    )

    private val CODE_TO_CHAR: Map<String, Char> = CODES.entries.associate { (c, code) -> code to c }

    /** 濁音 → 基本の仮名 */
    private val DAKUTEN: Map<Char, Char> = mapOf(
        'ガ' to 'カ', 'ギ' to 'キ', 'グ' to 'ク', 'ゲ' to 'ケ', 'ゴ' to 'コ', 'ザ' to 'サ', 'ジ' to 'シ', 'ズ' to 'ス',
        'ゼ' to 'セ', 'ゾ' to 'ソ', 'ダ' to 'タ', 'ヂ' to 'チ', 'ヅ' to 'ツ', 'デ' to 'テ', 'ド' to 'ト', 'バ' to 'ハ',
        'ビ' to 'ヒ', 'ブ' to 'フ', 'ベ' to 'ヘ', 'ボ' to 'ホ', 'ヴ' to 'ウ',
    )

    /** 半濁音 → 基本の仮名 */
    private val HANDAKUTEN: Map<Char, Char> = mapOf('パ' to 'ハ', 'ピ' to 'ヒ', 'プ' to 'フ', 'ペ' to 'ヘ', 'ポ' to 'ホ')
    private val DAKUTEN_BACK = DAKUTEN.entries.associate { (k, v) -> v to k }
    private val HANDAKUTEN_BACK = HANDAKUTEN.entries.associate { (k, v) -> v to k }

    /** 小書きの仮名 → 普通の仮名(和文モールスに小書きの符号は無い) */
    private val SMALL: Map<Char, Char> = mapOf(
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ', 'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ',
        'ヮ' to 'ワ', 'ヵ' to 'カ', 'ヶ' to 'ケ',
    )

    /** 符号から文字(見つからなければ null) */
    fun charFor(code: String): Char? = CODE_TO_CHAR[code]

    /**
     * 送る前の文章を、送れる形にそろえる: ひらがな → カタカナ、全角数字 → 半角、小書き → 普通、「」」→「。」、
     * 「(」「)」→ 全角の括弧。送れない文字(漢字・英字・記号)は除き、その一覧も返す。
     */
    fun normalize(text: String): Pair<String, Set<Char>> {
        val out = StringBuilder()
        val skipped = linkedSetOf<Char>()
        for (raw in text) {
            var c = raw
            if (c in 'ぁ'..'ゖ') c = c + ('ァ' - 'ぁ')  // ひらがな → カタカナ
            if (c in '０'..'９') c = '0' + (c - '０')
            c = SMALL[c] ?: c
            c = when (c) {
                '」' -> '。'; '(' -> '（'; ')' -> '）'; '｡' -> '。'; '､' -> '、'; else -> c
            }
            when {
                c.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')  // 語の区切り
                c in CODES || c in DAKUTEN || c in HANDAKUTEN -> out.append(c)
                else -> skipped += raw
            }
        }
        return out.toString().trim() to skipped
    }

    /** 1 文字を送る符号の列(濁音は 2 つ)。送れない文字は空。 */
    fun unitsFor(c: Char): List<String> = when (c) {
        in CODES -> listOf(CODES.getValue(c))
        in DAKUTEN -> listOf(CODES.getValue(DAKUTEN.getValue(c)), CODES.getValue('゛'))
        in HANDAKUTEN -> listOf(CODES.getValue(HANDAKUTEN.getValue(c)), CODES.getValue('゜'))
        else -> emptyList()
    }

    /** 「カ」+「゛」を「ガ」のように 1 文字にまとめる(受信した文字の表示用) */
    fun composeDakuten(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            val composed = when (next) {
                '゛' -> DAKUTEN_BACK[c]
                '゜' -> HANDAKUTEN_BACK[c]
                else -> null
            }
            if (composed != null) {
                out.append(composed); i += 2
            } else {
                out.append(c); i += 1
            }
        }
        return out.toString()
    }
}
