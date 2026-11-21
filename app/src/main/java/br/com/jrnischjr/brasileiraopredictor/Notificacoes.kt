package br.com.jrnischjr.brasileiraopredictor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class LembreteJogoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val casa = inputData.getString("casa") ?: "Mandante"
        val fora = inputData.getString("fora") ?: "Visitante"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "lembretes_jogos"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Lembretes de jogos", NotificationManager.IMPORTANCE_DEFAULT))
        val abrirApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("origem_notificacao", true)
            putExtra("time_mandante", casa)
            putExtra("time_visitante", fora)
        }
        val cliqueNotificacao = PendingIntent.getActivity(
            applicationContext,
            inputData.getString("chave")?.hashCode() ?: casa.hashCode(),
            abrirApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentIntent(cliqueNotificacao)
            .setContentTitle("Falta pouco para o jogo!")
            .setContentText("$casa x $fora começa em aproximadamente 30 minutos.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(inputData.getString("chave")?.hashCode() ?: casa.hashCode(), n)
        return Result.success()
    }
}

object AgendadorNotificacoes {
    fun agendar(context: Context, chave: String, casa: String, fora: String, dataUtc: String) {
        try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val inicio = f.parse(dataUtc.take(19))?.time ?: return
            val atraso = inicio - TimeUnit.MINUTES.toMillis(30) - System.currentTimeMillis()
            if (atraso <= 0) return
            val dados = workDataOf("casa" to casa, "fora" to fora, "chave" to chave)
            val pedido = OneTimeWorkRequestBuilder<LembreteJogoWorker>().setInputData(dados).setInitialDelay(atraso, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("lembrete_${chave.hashCode()}", ExistingWorkPolicy.REPLACE, pedido)
        } catch (erro: Exception) { Log.e("AgendadorNotificacoes", "Falha ao agendar lembrete para $casa x $fora", erro) }
    }
}
