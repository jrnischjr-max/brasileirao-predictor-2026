package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun nomeExibicaoClube(nome: String): String = when (nome.trim()) {
    "SC Corinthians Paulista" -> "Corinthians"
    else -> nome
}

@Composable
fun EscudoClube(
    url: String?,
    nome: String,
    tamanho: Dp = 34.dp
) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            contentDescription = "Escudo do $nome",
            modifier = Modifier.size(tamanho),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(tamanho)
                .clip(CircleShape)
                .background(Color(0xFF374151)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = nome.take(1).uppercase(),
                color = Color.White,
                fontSize = (tamanho.value * 0.45f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
