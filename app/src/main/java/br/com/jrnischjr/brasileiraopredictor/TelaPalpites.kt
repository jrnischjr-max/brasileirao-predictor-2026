package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
fun TelaPalpites(
    corDestaque: Color,
    corCartao: Color
) {
    val jogos = remember { mutableStateListOf<JSONObject>() }
    val clubes = remember { mutableStateListOf<JSONObject>() }

    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf("") }
    var jogosProntos by remember { mutableStateOf(false) }
    var tabelaPronta by remember { mutableStateOf(false) }

    fun carregarDados() {
        carregando = true
        erro = ""
        jogosProntos = false
        tabelaPronta = false

        DadosBrasileirao.buscarJogos { dados, mensagem ->
            if (dados != null) {
                jogos.clear()
                val lista = dados.optJSONArray("jogos")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { jogos.add(it) }
                    }
                }
                jogosProntos = true
            } else {
                erro = mensagem ?: "Erro ao buscar jogos."
                carregando = false
            }
        }

        DadosBrasileirao.buscarClassificacao { dados, mensagem ->
            if (dados != null) {
                clubes.clear()
                val lista = dados.optJSONArray("classificacao")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { clubes.add(it) }
                    }
                }
                tabelaPronta = true
            } else {
                erro = mensagem ?: "Erro ao buscar classificação."
                carregando = false
            }
        }
    }

    LaunchedEffect(Unit) {
        carregarDados()
    }

    LaunchedEffect(jogosProntos, tabelaPronta) {
        if (jogosProntos && tabelaPronta) {
            carregando = false
            if (jogos.isEmpty() || clubes.isEmpty()) {
                erro = "Não há dados suficientes para gerar os palpites."
            }
        }
    }

    fun probabilidadePoisson(gols: Double, quantidade: Int): Double {
        var resultado = exp(-gols)
        for (i in 1..quantidade) {
            resultado *= gols / i
        }
        return resultado
    }

    fun formatarData(data: String): String {
        return try {
            val entrada = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

            val convertida = entrada.parse(data.take(19))
                ?: return "Data a confirmar"

            val saida = java.text.SimpleDateFormat(
                "dd/MM/yyyy • HH:mm",
                java.util.Locale("pt", "BR")
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
            }

            saida.format(convertida)
        } catch (e: Exception) {
            "Data a confirmar"
        }
    }

    val proximosJogos = jogos
        .filter {
            val situacao = it.optString("situacao").uppercase()
            situacao != "FINISHED" &&
            situacao != "FINALIZADO" &&
            situacao != "AWARDED"
        }
        .sortedBy { it.optString("data") }
        .take(5)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "🎯 PALPITES INTELIGENTES",
            color = corDestaque,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Previsões estatísticas dos próximos jogos",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(15.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "COMO FUNCIONA",
                    color = corDestaque,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "Analisamos gols marcados e sofridos, desempenho na tabela e mando de campo para estimar as probabilidades.",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "As porcentagens são estimativas estatísticas, não garantias de resultado.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (carregando) {
            CircularProgressIndicator(color = corDestaque)
            Text(
                "Analisando os clubes e partidas...",
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (erro.isNotEmpty()) {
            Text(erro, color = Color.White)

            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = { carregarDados() }) {
                Text("ATUALIZAR DADOS")
            }
        }

        if (!carregando && erro.isEmpty() && jogosProntos && tabelaPronta) {
            if (proximosJogos.isEmpty()) {
                Text(
                    "Não há partidas futuras disponíveis no momento.",
                    color = Color.White
                )
            }

            proximosJogos.forEach { jogo ->
                val mandante = jogo.optJSONObject("mandante")
                    ?.optString("nome", "") ?: ""

                val visitante = jogo.optJSONObject("visitante")
                    ?.optString("nome", "") ?: ""

                val timeCasa = clubes.firstOrNull {
                    it.optString("nome").equals(mandante, true)
                }

                val timeFora = clubes.firstOrNull {
                    it.optString("nome").equals(visitante, true)
                }

                if (timeCasa != null && timeFora != null) {
                    val jogosCasa = timeCasa.optInt("jogos")
                    val jogosFora = timeFora.optInt("jogos")

                    if (jogosCasa > 0 && jogosFora > 0) {
                        val golsCasa = timeCasa.optDouble("golsPro") / jogosCasa
                        val sofridosCasa = timeCasa.optDouble("golsContra") / jogosCasa
                        val golsFora = timeFora.optDouble("golsPro") / jogosFora
                        val sofridosFora = timeFora.optDouble("golsContra") / jogosFora

                        val esperadoCasa = (
                            (golsCasa + sofridosFora) / 2.0 * 1.12
                        ).coerceIn(0.15, 3.5)

                        val esperadoFora = (
                            (golsFora + sofridosCasa) / 2.0 * 0.90
                        ).coerceIn(0.15, 3.5)

                        var vitoriaCasa = 0.0
                        var empate = 0.0
                        var vitoriaFora = 0.0

                        for (gCasa in 0..6) {
                            for (gFora in 0..6) {
                                val chance =
                                    probabilidadePoisson(esperadoCasa, gCasa) *
                                    probabilidadePoisson(esperadoFora, gFora)

                                when {
                                    gCasa > gFora -> vitoriaCasa += chance
                                    gCasa == gFora -> empate += chance
                                    else -> vitoriaFora += chance
                                }
                            }
                        }

                        val total = vitoriaCasa + empate + vitoriaFora

                        val pCasa = (vitoriaCasa / total * 100).roundToInt()
                        val pEmpate = (empate / total * 100).roundToInt()
                        val pFora = 100 - pCasa - pEmpate

                        val placarCasa = esperadoCasa.roundToInt()
                        val placarFora = esperadoFora.roundToInt()

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = corCartao
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    "RODADA ${jogo.optString("rodada")}",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )

                                Text(
                                    formatarData(jogo.optString("data")),
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    mandante,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )

                                Text(
                                    "              x",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Black
                                )

                                Text(
                                    visitante,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    "PROBABILIDADES ESTIMADAS",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Casa", color = Color.White, fontSize = 12.sp)
                                    Text("Empate", color = Color.White, fontSize = 12.sp)
                                    Text("Fora", color = Color.White, fontSize = 12.sp)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("$pCasa%", color = corDestaque, fontWeight = FontWeight.Bold)
                                    Text("$pEmpate%", color = corDestaque, fontWeight = FontWeight.Bold)
                                    Text("$pFora%", color = corDestaque, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    "🎯 PLACAR ESTIMADO: $placarCasa x $placarFora",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { carregarDados() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ATUALIZAR PALPITES")
            }
        }
    }
}
