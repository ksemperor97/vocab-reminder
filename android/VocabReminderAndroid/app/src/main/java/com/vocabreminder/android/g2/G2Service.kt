package com.vocabreminder.android.g2

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.vocabreminder.android.R
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.notifications.*
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.sync.*
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class G2Service : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    @Volatile private var closing = false
    private val prefs get() = getSharedPreferences("g2_connection", MODE_PRIVATE)
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("g2_session", "G2 연결 상태", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, G2ConnectActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(79800, NotificationCompat.Builder(this,"g2_session").setSmallIcon(R.drawable.ic_notification_review)
            .setContentTitle("G2 복습 연결 중").setContentText("연결 설정에서 종료할 수 있습니다.").setContentIntent(open).setOngoing(true).build())
        if(intent?.action == "g2.stop" || intent?.action == "g2.reset") {
            closing=true
            val reset=intent.action == "g2.reset"
            executor.execute {
                prefs.getString("session",null)?.let { runCatching { post("/g2/host",JSONObject().put("id",it).put("close",true)) } }
                prefs.edit().clear().putString("status",if(reset) "새 연결 코드 발급 중…" else "연결 종료됨").commit()
                if(reset) closing=false else main.post { stopSelf() }
            }
        }
        if(!started) {
            started=true
            executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                prefs.edit().putString("status", "연결 재시도 중 · 서버와 인터넷을 확인하세요.").apply()
            } },0,3,TimeUnit.SECONDS)
        }
        return START_STICKY
    }
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block); main.post(task); return task.get(10,TimeUnit.SECONDS)
    }
    private fun post(path: String, payload: JSONObject): JSONObject {
        val c=LocalVocabularyStore(this).cloudSyncCredentials()
        require(c.serverBaseUrl.startsWith("https://")) { "HTTPS 서버가 필요합니다." }
        return HttpUrlConnectionSyncTransport().postJson(c.serverBaseUrl.trimEnd('/')+path,payload,c.bearerToken,UUID.randomUUID().toString()).body
    }
    private fun tick() {
        if(closing) return
        var id=prefs.getString("session",null)
        if(id==null) {
            val created=post("/g2/create",JSONObject())
            id=created.getString("id")
            check(prefs.edit().putString("session",id).putString("code",created.getString("code")).commit())
        }
        val prompt=onMain { currentPrompt() }
        val body=JSONObject().put("id",id).put("prompt",prompt ?: JSONObject.NULL)
        prefs.getString("result",null)?.let { body.put("result",JSONObject(it)) }
        val result=post("/g2/host",body)
        prefs.edit().remove("result").putString("status",if(result.optBoolean("paired")) "G2 연결됨 · 복습 알림을 기다립니다." else "Even 앱에서 아래 연결 코드를 입력하세요. (발급 후 10분)").apply()
        val pending=result.optJSONObject("pending") ?: return
        val outcome=onMain { apply(pending) }
        check(prefs.edit().putString("result",JSONObject().put("id",pending.getString("id")).put("status",outcome).toString()).commit())
    }
    private fun currentPrompt(): JSONObject? {
        val store=LocalVocabularyStore(this)
        val active=ReminderNotifications.activeNotificationState(this) ?: return null
        if(active.phase != NotificationPhase.PROMPT) return null
        val card=store.findCard(active.cardId) ?: return null
        if(card.dueAt.isAfter(Instant.now())) return null
        val revision="${card.version}:${card.updatedAt}"
        return JSONObject().put("id","${card.id}:$revision:${active.postedAt}").put("cardId",card.id)
            .put("revision",revision).put("word",card.displayWord.take(120)).put("meaning",card.meaningKo.take(2000))
    }
    private fun apply(action: JSONObject): String {
        val store=LocalVocabularyStore(this)
        // A committed event is the durable receipt, even if the service died before HTTP acknowledgement.
        if(store.events().any { it.metadata["g2_action_id"]==action.getString("id") }) return "applied"
        if(currentPrompt()?.getString("id") != action.getString("promptId")) return "stale"
        val now=Instant.now()
        val result=store.applyG2Response(action.getString("cardId"),action.getString("revision"),action.getString("id"),ReviewResponse.valueOf(action.getString("response").uppercase()),now)
        if(result=="applied") {
            val card=store.findCard(action.getString("cardId"))!!
            ReminderNotifications.clearActivePrompt(this,card.id)
            ReminderNotifications.cancel(this,card)
            ReminderScheduler.schedule(this,card,now)
            ReminderNotifications.scheduleQueueAdvance(this,now=now)
            SyncWorker.enqueueImmediate(this)
        }
        return result
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
