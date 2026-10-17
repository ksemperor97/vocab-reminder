package com.vocabreminder.android.g2

import android.app.Activity
import android.content.*
import android.os.*
import android.widget.*
import com.vocabreminder.android.data.LocalVocabularyStore

class G2ConnectActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val prefs get()=getSharedPreferences("g2_connection",MODE_PRIVATE)
    private val refresh=object : Runnable {
        override fun run() {
            status.text="${prefs.getString("status","연결 시작을 눌러주세요.")}\n\n연결 코드: ${prefs.getString("code","—")}"
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32) }
        fun label(text: String)=TextView(this).apply { this.text=text;textSize=18f;layout.addView(this) }
        label("G2 링 복습\n\n연결 후 Even의 Vocab 앱을 실행해 두세요. 복습 알림의 문제와 링 응답을 실제 학습 기록에 반영합니다.\n")
        status=label("")
        fun button(text: String,action: () -> Unit) { layout.addView(Button(this).apply { this.text=text;setOnClickListener { action() } }) }
        button("연결 시작") {
            val c=LocalVocabularyStore(this).cloudSyncCredentials()
            if(!c.serverBaseUrl.startsWith("https://") || c.bearerToken.isBlank()) { Toast.makeText(this,"먼저 HTTPS 동기화 서버를 설정하세요.",Toast.LENGTH_LONG).show() }
            else startForegroundService(Intent(this,G2Service::class.java))
        }
        button("연결 코드 복사") {
            prefs.getString("code",null)?.let { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("G2 연결 코드",it)) }
        }
        button("새 연결 코드 발급") {
            startForegroundService(Intent(this,G2Service::class.java).setAction("g2.reset"))
        }
        button("연결 종료") {
            startForegroundService(Intent(this,G2Service::class.java).setAction("g2.stop"))
        }
        label("\n휴대폰 설정에서 이 앱과 Even 앱의 백그라운드 실행을 허용하세요. 종료하거나 연결이 끊기면 G2의 자동 표시도 멈춥니다.")
        setContentView(layout)
    }
    override fun onResume() { super.onResume();handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh);super.onPause() }
}
