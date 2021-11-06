package net.torvald.terrarum.gamecontroller

import TypewriterGDX
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input

/**
 * Created by minjaesong on 2021-11-06.
 */
class InputStrober(val typewriter: TypewriterGDX) {

    companion object {
        const val KEY_DOWN = 0
        const val KEY_CHANGE = 1
        const val N_KEY_ROLLOVER = 8
    }

    var KEYBOARD_DELAYS = longArrayOf(0L,250000000L,0L,25000000L,0L)
    private var stroboTime = 0L
    private var stroboStatus = 0
    private var repeatCount = 0
    private var oldKeys = IntArray(N_KEY_ROLLOVER) { 0 }
    /** always Low Layer */
//        private var keymap = IME.getLowLayerByName(App.getConfigString("basekeyboardlayout"))

    private val thread = Thread { while (!Thread.interrupted()) {
        if (Gdx.input != null) withKeyboardEvent()
    } }

    init {
//        println("InputStrobe start")
        thread.start()
    }

    fun dispose() {
        thread.interrupt()
    }

    fun resetKeyboardStrobo() {
        stroboStatus = 0
        repeatCount = 0
    }

    // code proudly stolen from tsvm's TVDOS.SYS
    private fun withKeyboardEvent() {
        val keys = strobeKeys()
        var keyChanged = !arrayEq(keys, oldKeys)
        val keyDiff = arrayDiff(keys, oldKeys)

//        println("Key strobed: ${keys.joinToString()}")

        if (stroboStatus % 2 == 0 && keys[0] != 0) {
            stroboStatus += 1
            stroboTime = System.nanoTime()
            repeatCount += 1

            val shiftin = keys.contains(Input.Keys.SHIFT_LEFT) || keys.contains(Input.Keys.SHIFT_RIGHT)
            val newKeysym0 = keysToStr(keyDiff)

            val newKeysym = if (newKeysym0 == null) null
            else if (shiftin && newKeysym0.size > 1 && newKeysym0[1]?.isNotBlank() == true) newKeysym0[1]
            else newKeysym0[0]

            val headKeyCode = (if (keyDiff.size < 1) keys[0] else keyDiff[0]).and(127) or (if (shiftin) 128 else 0)

            if (repeatCount == 1) {
                if (!keyChanged) {
//                    println("KEY_DOWN '$keysym' ($headKeyCode) $repeatCount; ${keys.joinToString()}")
//                App.inputStrobed(TerrarumKeyboardEvent(KEY_DOWN, keysym, headKeyCode, repeatCount, keys))
                    typewriter.acceptKey(headKeyCode)
                } else if (newKeysym != null) {
//                    println("KEY_DOWC '$newKeysym' ($headKeyCode) $repeatCount; ${keys.joinToString()}")
//                App.inputStrobed(TerrarumKeyboardEvent(KEY_DOWN, newKeysym, headKeyCode, repeatCount, keys))
                    typewriter.acceptKey(headKeyCode)
                }
            }

            oldKeys = keys // don't put this outside of if-cascade
        }
        else if (keyChanged || keys[0] == 0) {
            stroboStatus = 0
            repeatCount = 0

            if (keys[0] == 0) keyChanged = false
        }
        else if (stroboStatus % 2 == 1 && System.nanoTime() - stroboTime < KEYBOARD_DELAYS[stroboStatus]) {
            Thread.sleep(1L)
        }
        else {
            stroboStatus += 1
            if (stroboStatus >= 4)
                stroboStatus = 2
        }
    }

    private fun keysToStr(keys: IntArray): Array<String?>? {
        if (keys.isEmpty()) return null
        val headkey = keys[0]
        return keymap[headkey]
    }

    private fun strobeKeys(): IntArray {
        var keysPushed = 0
        val keyEventBuffers = IntArray(N_KEY_ROLLOVER) { 0 }
        for (k in 1..254) {
            if (Gdx.input.isKeyPressed(k)) {
                keyEventBuffers[keysPushed] = k
                keysPushed += 1
            }

            if (keysPushed >= N_KEY_ROLLOVER) break
        }
        return keyEventBuffers
    }

    private fun arrayEq(a: IntArray, b: IntArray): Boolean {
        for (i in a.indices) {
            if (a[i] != b.getOrNull(i)) return false
        }
        return true
    }

    private fun arrayDiff(a: IntArray, b: IntArray): IntArray {
        return a.filter { !b.contains(it) }.toIntArray()
    }


    private val keymap = arrayOf(arrayOf<String?>(""),arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>("<HOME>"),
        arrayOf<String?>(null),
        arrayOf<String?>("<CALL>"),
        arrayOf<String?>("<ENDCALL>"),
        arrayOf<String?>("0",")"),
        arrayOf<String?>("1","!"),
        arrayOf<String?>("2","@"),
        arrayOf<String?>("3","#"),
        arrayOf<String?>("4","$"),
        arrayOf<String?>("5","%"),
        arrayOf<String?>("6","^"),
        arrayOf<String?>("7","&"),
        arrayOf<String?>("8","*"),
        arrayOf<String?>("9","("),
        arrayOf<String?>("*"),
        arrayOf<String?>("#"),
        arrayOf<String?>("<UP>"),
        arrayOf<String?>("<DOWN>"),
        arrayOf<String?>("<LEFT>"),
        arrayOf<String?>("<RIGHT>"),
        arrayOf<String?>("<CENTER>"),
        arrayOf<String?>("<VOL_UP>"),
        arrayOf<String?>("<VOL_DOWN>"),
        arrayOf<String?>("<POWER>"),
        arrayOf<String?>("<CAMERA>"),
        arrayOf<String?>("<CLEAR>"),
        arrayOf<String?>("a","A"),
        arrayOf<String?>("b","B"),
        arrayOf<String?>("c","C"),
        arrayOf<String?>("d","D"),
        arrayOf<String?>("e","E"),
        arrayOf<String?>("f","F"),
        arrayOf<String?>("g","G"),
        arrayOf<String?>("h","H"),
        arrayOf<String?>("i","I"),
        arrayOf<String?>("j","J"),
        arrayOf<String?>("k","K"),
        arrayOf<String?>("l","L"),
        arrayOf<String?>("m","M"),
        arrayOf<String?>("n","N"),
        arrayOf<String?>("o","O"),
        arrayOf<String?>("p","P"),
        arrayOf<String?>("q","Q"),
        arrayOf<String?>("r","R"),
        arrayOf<String?>("s","S"),
        arrayOf<String?>("t","T"),
        arrayOf<String?>("u","U"),
        arrayOf<String?>("v","V"),
        arrayOf<String?>("w","W"),
        arrayOf<String?>("x","X"),
        arrayOf<String?>("y","Y"),
        arrayOf<String?>("z","Z"),
        arrayOf<String?>(",","<"),
        arrayOf<String?>(".",">"),
        arrayOf<String?>("<ALT_L>"),
        arrayOf<String?>("<ALT_R>"),
        arrayOf<String?>("<SHIFT_L>"),
        arrayOf<String?>("<SHIFT_R>"),
        arrayOf<String?>("<TAB>"),
        arrayOf<String?>(" "),
        arrayOf<String?>("<SYM>"),
        arrayOf<String?>("<EXPLORER>"),
        arrayOf<String?>("<ENVELOPE>"),
        arrayOf<String?>("\n"),
        arrayOf<String?>("\u0008"),
        arrayOf<String?>("`","~"),
        arrayOf<String?>("-","_"),
        arrayOf<String?>("=","+"),
        arrayOf<String?>("arrayOf<String?>(","{"),
        arrayOf<String?>(")","}"),
        arrayOf<String?>("\\","|"),
        arrayOf<String?>(";",":"),
        arrayOf<String?>("'","\""),
        arrayOf<String?>("/","?"),
        arrayOf<String?>("<AT>"),
        arrayOf<String?>("<NUM_LOCK>"),
        arrayOf<String?>("<HEADSETHOOK>"),
        arrayOf<String?>("<FOCUS>"),
        arrayOf<String?>("+"),
        arrayOf<String?>("<MENU>"),
        arrayOf<String?>("<NOTIFICATION>"),
        arrayOf<String?>("<SEARCH>"),
        arrayOf<String?>("<PLAY_PAUSE>"),
        arrayOf<String?>("<STOP>"),
        arrayOf<String?>("<NEXT>"),
        arrayOf<String?>("<PREV>"),
        arrayOf<String?>("<REW>"),
        arrayOf<String?>("<FFWD>"),
        arrayOf<String?>("<MUTE>"),
        arrayOf<String?>("<PAGE_UP>"),
        arrayOf<String?>("<PAGE_DOWN>"),
        arrayOf<String?>("<PICTSYMBOLS>"),
        arrayOf<String?>("<SW:>TCH_CHARSET>"),
        arrayOf<String?>("<:A:>"),
        arrayOf<String?>("<:B:>"),
        arrayOf<String?>("<:C:>"),
        arrayOf<String?>("<:X:>"),
        arrayOf<String?>("<:Y:>"),
        arrayOf<String?>("<:Z:>"),
        arrayOf<String?>("<:L1:>"),
        arrayOf<String?>("<:R1:>"),
        arrayOf<String?>("<:L2:>"),
        arrayOf<String?>("<:R2:>"),
        arrayOf<String?>("<:TL:>"),
        arrayOf<String?>("<:TR:>"),
        arrayOf<String?>("<:START:>"),
        arrayOf<String?>("<:SELECT:>"),
        arrayOf<String?>("<:MODE:>"),
        arrayOf<String?>("<ESC>"),
        arrayOf<String?>("<DEL>"),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>("<CAPS_LOCK>"),
        arrayOf<String?>("<SCROLL_LOCK>"),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>("<PRINT_SCREEN_SYS_RQ>"),
        arrayOf<String?>("<PAUSE_BREAK>"),
        arrayOf<String?>(null),
        arrayOf<String?>("<END>"),
        arrayOf<String?>("<INSERT>"),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>("<CTRL_L>"),
        arrayOf<String?>("<CTRL_R>"),
        arrayOf<String?>("<F1>"),
        arrayOf<String?>("<F2>"),
        arrayOf<String?>("<F3>"),
        arrayOf<String?>("<F4>"),
        arrayOf<String?>("<F5>"),
        arrayOf<String?>("<F6>"),
        arrayOf<String?>("<F7>"),
        arrayOf<String?>("<F8>"),
        arrayOf<String?>("<F9>"),
        arrayOf<String?>("<F10>"),
        arrayOf<String?>("<F11>"),
        arrayOf<String?>("<F12>"),
        arrayOf<String?>("<NUM_LOCK>"),
        arrayOf<String?>("0"),
        arrayOf<String?>("1"),
        arrayOf<String?>("2"),
        arrayOf<String?>("3"),
        arrayOf<String?>("4"),
        arrayOf<String?>("5"),
        arrayOf<String?>("6"),
        arrayOf<String?>("7"),
        arrayOf<String?>("8"),
        arrayOf<String?>("9"),
        arrayOf<String?>("/"),
        arrayOf<String?>("*"),
        arrayOf<String?>("-"),
        arrayOf<String?>("+"),
        arrayOf<String?>("."),
        arrayOf<String?>("."),
        arrayOf<String?>("\n"),
        arrayOf<String?>("="),
        arrayOf<String?>("("),
        arrayOf<String?>(")"),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>(null),
        arrayOf<String?>("<:CIRCLE:>")
    )

}


data class TerrarumKeyboardEvent(
    val type: Int,
    val character: String?, // representative key symbol
    val headkey: Int, // representative keycode
    val repeatCount: Int,
    val keycodes: IntArray
)