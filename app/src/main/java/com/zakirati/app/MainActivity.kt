package com.zakirati.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.DateFormat
import java.util.*

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var notes = mutableListOf<Note>()
    private var recording: MediaRecorder? = null
    private var currentRecordingPath = ""
    private var dark = false
    private val prefs by lazy { getSharedPreferences("thakirati_settings", 0) }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else toast("يلزم السماح باستخدام الميكروفون لتسجيل الملاحظة الصوتية")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val zip = java.util.zip.ZipOutputStream(out)
                val json = JSONArray()
                notes.forEach { n ->
                    json.put(JSONObject().put("id",n.id).put("title",n.title).put("body",n.body)
                        .put("audioPath", if (n.audioPath.isNotBlank()) File(n.audioPath).name else "")
                        .put("reminderAt",n.reminderAt).put("completed",n.completed).put("createdAt",n.createdAt))
                }
                zip.putNextEntry(java.util.zip.ZipEntry("notes.json"))
                zip.write(json.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                notes.filter { it.audioPath.isNotBlank() && File(it.audioPath).exists() }.forEach { n ->
                    zip.putNextEntry(java.util.zip.ZipEntry("audio/${File(n.audioPath).name}"))
                    File(n.audioPath).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                zip.close()
            }
            toast("تم إنشاء النسخة الاحتياطية")
        } catch (e: Exception) { toast("تعذر إنشاء النسخة الاحتياطية") }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val imported = mutableListOf<Note>()
            contentResolver.openInputStream(uri)!!.use { input ->
                val zip = java.util.zip.ZipInputStream(input)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "notes.json") {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            imported.add(Note(o.optString("id"), o.optString("title"), o.optString("body"),
                                o.optString("audioPath"), o.optLong("reminderAt"), o.optBoolean("completed"), o.optLong("createdAt")))
                        }
                    } else if (entry.name.startsWith("audio/")) {
                        val target = File(NoteStore.audioDir(this), File(entry.name).name)
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
                zip.close()
            }
            if (imported.isNotEmpty()) {
                imported.forEach { n ->
                    if (n.audioPath.isNotBlank()) {
                        val f = File(NoteStore.audioDir(this), File(n.audioPath).name)
                        if (f.exists()) n.audioPath = f.absolutePath else n.audioPath = ""
                    }
                }
                notes = imported
                NoteStore.save(this, notes)
                notes.filter { it.reminderAt > System.currentTimeMillis() && !it.completed }.forEach { scheduleReminder(this, it) }
                showHome()
                toast("تمت استعادة النسخة الاحتياطية")
            } else toast("لم يتم العثور على ملاحظات في الملف")
        } catch (e: Exception) { toast("تعذر قراءة النسخة الاحتياطية؛ تأكد من اختيار ملف ذاكرتي الصحيح") }
    }
    private val speechResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) createNote(initialBody = spoken)
        } else toast("لم يتم تحويل الكلام إلى نص")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notes = NoteStore.all(this)
        dark = prefs.getBoolean("dark", false)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.getBoolean("lockEnabled", false)) {
            val pin = prefs.getString("pin", "")
            if (!pin.isNullOrBlank()) askPin()
            else if (prefs.getBoolean("bioEnabled", false)) askBiometric()
        } else showHome()
    }

    private fun setupRoot() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 12) }
        root.setBackgroundColor(if (dark) Color.rgb(16,18,23) else Color.rgb(245,249,255))
        setContentView(root)
    }
    private fun text(s: String, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply { text=s; textSize=size; setTextColor(if (dark) Color.rgb(245,195,75) else Color.rgb(20,76,150)); gravity=Gravity.CENTER; if (bold) setTypeface(null, 1); setPadding(6,10,6,10) }
    private fun button(s: String, action: () -> Unit): Button =
        Button(this).apply { text=s; setOnClickListener { action() } }
    private fun add(v: View) { root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 }) }
    private fun showHome() {
        setupRoot()
        add(text("ذاكرتي", 30f, true)); add(text("ذاكرتك التي لا تنسى", 15f))
        add(button("＋ إضافة ملاحظة نصية", { createNote() }))
        add(button("🎙 تسجيل ملاحظة صوتية", { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startRecording() else audioPermission.launch(Manifest.permission.RECORD_AUDIO) }))
        add(button("🎤 تحويل الكلام إلى نص", { startSpeech() }))
        add(button("🔍 البحث في الملاحظات", { searchNotes() }))
        add(button("⚙ الإعدادات", { showSettings() }))
        add(button("💾 إنشاء نسخة احتياطية", { createBackup.launch("Thakirati-backup.zip") }))
        add(button("📂 استعادة نسخة احتياطية", { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        notes.sortedByDescending { it.createdAt }.forEach { n ->
            val card = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(10,8,10,8); setBackgroundColor(if (dark) Color.rgb(30,34,42) else Color.WHITE) }
            card.addView(text(n.title.ifBlank { "ملاحظة بدون عنوان" }, 18f, true))
            if (n.body.isNotBlank()) card.addView(text(n.body, 14f))
            if (n.audioPath.isNotBlank()) card.addView(button("▶ تشغيل التسجيل", { playAudio(n.audioPath) }))
            if (n.reminderAt > 0) card.addView(text("التذكير: " + DateFormat.getDateTimeInstance().format(Date(n.reminderAt)), 12f))
            card.addView(button("✏ تعديل", { editNote(n) }))
            card.addView(button(if (n.completed) "إلغاء الإكمال" else "✓ وضع كمكتملة", { n.completed=!n.completed; persist(); showHome() }))
            card.addView(button("🗑 حذف", { confirmDelete(n) }))
            list.addView(card, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=10 })
        }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun createNote(initialBody: String = "") {
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        val title = EditText(this).apply { hint="عنوان الملاحظة"; setTextColor(if(dark) Color.WHITE else Color.BLACK) }
        val body = EditText(this).apply { hint="اكتب ملاحظتك هنا"; minLines=4; setText(initialBody); gravity=Gravity.TOP; setTextColor(if(dark) Color.WHITE else Color.BLACK) }
        val date = EditText(this).apply { hint="موعد التذكير (اختياري)"; isFocusable=false }
        var reminder = 0L
        date.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y,m,d)
                TimePickerDialog(this, { _, h, min ->
                    cal.set(Calendar.HOUR_OF_DAY,h); cal.set(Calendar.MINUTE,min); cal.set(Calendar.SECOND,0)
                    reminder=cal.timeInMillis; date.setText(DateFormat.getDateTimeInstance().format(Date(reminder)))
                },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()
            },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(title); layout.addView(body); layout.addView(date)
        AlertDialog.Builder(this).setTitle("ملاحظة جديدة").setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                val n = Note(UUID.randomUUID().toString(), title.text.toString(), body.text.toString(), reminderAt=reminder)
                notes.add(n); persist()
                if (reminder > System.currentTimeMillis()) scheduleReminder(this,n)
                showHome()
            }.setNegativeButton("إلغاء",null).show()
    }
    private fun editNote(n: Note) {
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        val title=EditText(this).apply { setText(n.title); hint="العنوان" }
        val body=EditText(this).apply { setText(n.body); hint="التفاصيل"; minLines=4 }
        val date=EditText(this).apply { hint="اضغط لتحديد موعد"; isFocusable=false; if(n.reminderAt>0) setText(DateFormat.getDateTimeInstance().format(Date(n.reminderAt))) }
        date.setOnClickListener {
            val c=Calendar.getInstance()
            DatePickerDialog(this,{_,y,m,d-> c.set(y,m,d); TimePickerDialog(this,{_,h,mi->c.set(Calendar.HOUR_OF_DAY,h);c.set(Calendar.MINUTE,mi);n.reminderAt=c.timeInMillis;date.setText(DateFormat.getDateTimeInstance().format(Date(n.reminderAt)))},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show()},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(title);layout.addView(body);layout.addView(date)
        AlertDialog.Builder(this).setTitle("تعديل الملاحظة").setView(layout).setPositiveButton("حفظ"){_,_->
            n.title=title.text.toString();n.body=body.text.toString();persist()
            if(n.reminderAt>System.currentTimeMillis()) scheduleReminder(this,n)
            showHome()
        }.setNegativeButton("إلغاء",null).show()
    }
    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this).setTitle("حذف الملاحظة؟").setMessage("سيتم حذف الملاحظة من التطبيق.")
            .setPositiveButton("حذف") { _, _ ->
                if(n.audioPath.isNotBlank()) File(n.audioPath).delete()
                notes.removeAll { it.id==n.id }; persist(); showHome()
            }.setNegativeButton("إلغاء",null).show()
    }
    private fun startRecording() {
        try {
            val f=File(NoteStore.audioDir(this),"voice_${System.currentTimeMillis()}.m4a")
            currentRecordingPath=f.absolutePath
            recording=if(Build.VERSION.SDK_INT>=31) MediaRecorder(this) else createLegacyRecorder()
            recording?.apply { setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setOutputFile(currentRecordingPath);prepare();start() }
            AlertDialog.Builder(this).setTitle("جارٍ التسجيل 🎙").setMessage("تحدث الآن. اضغط إيقاف عند الانتهاء.")
                .setPositiveButton("إيقاف وحفظ") { _, _ ->
                    try { recording?.stop(); recording?.release(); recording=null
                        val n=Note(UUID.randomUUID().toString(),"ملاحظة صوتية","",currentRecordingPath)
                        notes.add(n);persist();showHome()
                    } catch(e:Exception) { File(currentRecordingPath).delete(); toast("لم يكتمل التسجيل") }
                }.setNegativeButton("إلغاء") { _, _ -> try { recording?.stop(); recording?.release() } catch (_:Exception) {}; recording=null; File(currentRecordingPath).delete() }.setOnCancelListener { try { recording?.stop(); recording?.release() } catch (_:Exception) {}; recording=null }.show()
        } catch(e:Exception) { toast("تعذر بدء التسجيل. تحقق من إذن الميكروفون.") }
    }
    @Suppress("DEPRECATION")
    private fun createLegacyRecorder(): MediaRecorder = MediaRecorder()

    private fun playAudio(path: String) {
        try { MediaPlayer().apply { setDataSource(path); setOnCompletionListener { it.release() }; prepare(); start() } }
        catch(e:Exception) { toast("تعذر تشغيل التسجيل") }
    }
    private fun startSpeech() {
        try {
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ar")
                putExtra(RecognizerIntent.EXTRA_PROMPT,"تحدث لتسجيل الملاحظة")
            }
            speechResult.launch(i)
        } catch(e:Exception) { toast("تحويل الكلام إلى نص غير متاح على هذا الهاتف. يمكن استخدام التسجيل الصوتي بدلًا منه.") }
    }
    private fun searchNotes() {
        val input=EditText(this).apply { hint="ابحث في العناوين والتفاصيل" }
        AlertDialog.Builder(this).setTitle("البحث").setView(input).setPositiveButton("بحث"){_,_->
            val q=input.text.toString()
            val found=notes.filter { it.title.contains(q,true)||it.body.contains(q,true) }
            AlertDialog.Builder(this).setTitle("نتائج البحث").setItems(found.map { it.title.ifBlank{"ملاحظة بدون عنوان"} }.toTypedArray()) { _, which -> editNote(found[which]) }
                .setPositiveButton("إغلاق",null).show()
        }.setNegativeButton("إلغاء",null).show()
    }
    private fun showSettings() {
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,10,20,10) }
        val theme=Switch(this).apply { text="الوضع الليلي الأسود والذهبي"; isChecked=dark }
        val lock=Switch(this).apply { text="تفعيل قفل التطبيق"; isChecked=prefs.getBoolean("lockEnabled",false) }
        val bio=Switch(this).apply { text="السماح بفتح التطبيق بالبصمة"; isChecked=prefs.getBoolean("bioEnabled",false) }
        layout.addView(theme);layout.addView(lock);layout.addView(bio)
        AlertDialog.Builder(this).setTitle("الإعدادات والخصوصية").setView(layout)
            .setPositiveButton("حفظ"){_,_->
                dark=theme.isChecked;prefs.edit().putBoolean("dark",dark).putBoolean("lockEnabled",lock.isChecked).putBoolean("bioEnabled",bio.isChecked).apply()
                if(lock.isChecked && prefs.getString("pin","").isNullOrBlank()) promptSetPin()
                else showHome()
            }.setNegativeButton("إلغاء",null).show()
    }
    private fun promptSetPin() {
        val input=EditText(this).apply { hint="أنشئ رقمًا سريًا من 4 أرقام"; inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("إعداد القفل").setMessage("احفظ الرقم السري في مكان آمن.")
            .setView(input).setPositiveButton("حفظ"){_,_->
                val pin=input.text.toString()
                if(pin.length>=4) { prefs.edit().putString("pin",pin).apply(); showHome() } else { toast("استخدم 4 أرقام على الأقل"); showHome() }
            }.setNegativeButton("إلغاء"){_,_->showHome()}.show()
    }
    private fun askPin() {
        val input=EditText(this).apply { hint="الرقم السري"; inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("فتح ذاكرتي").setView(input).setCancelable(false)
            .setPositiveButton("فتح"){_,_->
                if(input.text.toString()==prefs.getString("pin","")) {
                    if(prefs.getBoolean("bioEnabled",false)) askBiometric() else showHome()
                } else { toast("الرقم السري غير صحيح"); askPin() }
            }.show()
    }
    private fun askBiometric() {
        val can=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if(can!=BiometricManager.BIOMETRIC_SUCCESS) { askPin(); return }
        val executor=ContextCompat.getMainExecutor(this)
        val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { showHome() }
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence) { if(prefs.getString("pin","").isNullOrBlank()) showHome() else askPin() }
            override fun onAuthenticationFailed() { }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("فتح ذاكرتي").setSubtitle("استخدم بصمة الإصبع أو وسيلة التحقق المدعومة").setNegativeButtonText("استخدام الرقم السري").build())
    }
    private fun persist() = NoteStore.save(this,notes)
    private fun toast(s:String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()

    companion object {
        fun scheduleReminder(context: Context, n: Note) {
            val intent=Intent(context,ReminderReceiver::class.java).putExtra("title",n.title.ifBlank{"تذكير من ذاكرتي"}).putExtra("body",n.body.ifBlank{"حان موعد ملاحظتك"})
            val pi=PendingIntent.getBroadcast(context,n.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am=context.getSystemService(ALARM_SERVICE) as AlarmManager
            try {
                if(Build.VERSION.SDK_INT>=23) {
                    if(Build.VERSION.SDK_INT<31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,n.reminderAt,pi)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,n.reminderAt,pi)
                } else am.setExact(AlarmManager.RTC_WAKEUP,n.reminderAt,pi)
            } catch(_:SecurityException) { am.set(AlarmManager.RTC_WAKEUP,n.reminderAt,pi) }
        }
    }
}
