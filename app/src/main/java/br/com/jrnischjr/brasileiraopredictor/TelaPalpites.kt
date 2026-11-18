
package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

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
                    "O motor analisa os últimos cinco jogos, o desempenho em casa e fora, gols marcados e sofridos e a força dos adversários. Um modelo estatístico de Poisson calcula as probabilidades.",
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

                val analise = MotorPalpites.analisar(
                    jogo = jogo,
                    todosJogos = jogos.toList(),
                    clubes = clubes.toList()
                )

                if (analise != null) {
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
                                "Forma recente: ${analise.formaCasa}",
                                color = corDestaque,
                                fontSize = 11.sp
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

                            Text(
                                "Forma recente: ${analise.formaFora}",
                                color = corDestaque,
                                fontSize = 11.sp
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
                                Text(
                                    "${analise.probCasa}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${analise.probEmpate}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${analise.probFora}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "🎯 PLACAR ESTIMADO: ${analise.placarCasa} x ${analise.placarFora}",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )
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
