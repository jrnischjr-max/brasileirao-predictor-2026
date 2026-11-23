#!/usr/bin/env python3
"""Laboratório independente do motor 2.0. Não altera o motor oficial nem dados do usuário."""
from __future__ import annotations

import json
import math
import os
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

BASE_URL = os.environ.get(
    "BRASILEIRAO_API_URL",
    "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/",
)
SEASON = int(os.environ.get("SEASON", "2025"))
OUTPUT = Path("laboratorio-output")
FINISHED = {"FINISHED", "FINALIZADO", "AWARDED"}
CLASSES = ("casa", "empate", "fora")


@dataclass(frozen=True)
class Match:
    date: str
    home: str
    away: str
    hg: int
    ag: int
    round: int | None = None


def fetch_matches() -> list[dict]:
    url = BASE_URL + ("&" if "?" in BASE_URL else "?") + urllib.parse.urlencode({"endpoint": "fd-history"})
    request = urllib.request.Request(url, headers={"User-Agent": "BrasileiraoPredictor-Lab/2.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    rows = payload.get("jogos") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        raise ValueError("A API não retornou a lista esperada em 'jogos'.")
    return rows


def normalize(rows: list[dict]) -> list[Match]:
    matches = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        season = row.get("temporada")
        date = str(row.get("data") or "")[:10]
        if not date or not date[:4].isdigit():
            continue
        if int(season or date[:4]) != SEASON:
            continue
        if str(row.get("situacao") or "").upper() not in FINISHED:
            continue
        home = row.get("mandante") or {}
        away = row.get("visitante") or {}
        score = row.get("placar") or {}
        if not isinstance(home, dict) or not isinstance(away, dict) or not isinstance(score, dict):
            continue
        hn, an = str(home.get("nome") or "").strip(), str(away.get("nome") or "").strip()
        try:
            hg, ag = int(score["mandante"]), int(score["visitante"])
        except (KeyError, TypeError, ValueError):
            continue
        if hn and an and hg >= 0 and ag >= 0:
            matches.append(Match(date, hn, an, hg, ag, row.get("rodada")))
    return sorted(matches, key=lambda m: (m.date, m.home, m.away))


def poisson(mean: float, goals: int) -> float:
    value = math.exp(-mean)
    for i in range(1, goals + 1):
        value *= mean / i
    return value


def normalize_probs(values: tuple[float, float, float]) -> tuple[float, float, float]:
    total = sum(values)
    if not math.isfinite(total) or total <= 0:
        return (1 / 3, 1 / 3, 1 / 3)
    return tuple(max(0.0, x) / total for x in values)


def elo_prob(match: Match, history: list[Match]) -> tuple[float, float, float]:
    ratings: dict[str, float] = {}
    draws = 0
    for old in history:
        h, a = old.home.casefold(), old.away.casefold()
        rh, ra = ratings.get(h, 1500.0), ratings.get(a, 1500.0)
        expected = 1.0 / (1.0 + 10.0 ** (-(rh + 65.0 - ra) / 400.0))
        actual = 1.0 if old.hg > old.ag else 0.5 if old.hg == old.ag else 0.0
        change = 20.0 * (actual - expected)
        ratings[h], ratings[a] = rh + change, ra - change
        draws += int(old.hg == old.ag)
    total = len(history)
    draw_rate = (draws + 5.4) / (total + 20.0)
    rh = ratings.get(match.home.casefold(), 1500.0)
    ra = ratings.get(match.away.casefold(), 1500.0)
    expected_home = 1.0 / (1.0 + 10.0 ** (-(rh + 65.0 - ra) / 400.0))
    decisive_home = min(1.0, max(0.0, (expected_home - draw_rate / 2.0) / (1.0 - draw_rate)))
    return normalize_probs(((1 - draw_rate) * decisive_home, draw_rate,
                            (1 - draw_rate) * (1 - decisive_home)))


def historical_team_stats(history: list[Match]) -> dict[str, dict[str, float]]:
    stats: dict[str, dict[str, float]] = {}
    for match in history:
        home = stats.setdefault(match.home.casefold(), {"name": match.home, "games": 0, "points": 0})
        away = stats.setdefault(match.away.casefold(), {"name": match.away, "games": 0, "points": 0})
        home["games"] += 1
        away["games"] += 1
        if match.hg > match.ag:
            home["points"] += 3
        elif match.hg < match.ag:
            away["points"] += 3
        else:
            home["points"] += 1
            away["points"] += 1
    return stats


def recent_form(name: str, history: list[Match], stats: dict[str, dict[str, float]]) -> tuple[float, str]:
    recent = [m for m in history if m.home.casefold() == name.casefold()
              or m.away.casefold() == name.casefold()][-5:][::-1]
    if not recent:
        return 1.0, "Sem histórico"

    averages = [v["points"] / v["games"] for v in stats.values() if v["games"] > 0]
    league_ppg = sum(averages) / len(averages) if averages else 1.5
    weighted_points = weight_total = goal_diff = 0.0
    form = []
    for index, match in enumerate(recent):
        weight = float(5 - index)
        at_home = match.home.casefold() == name.casefold()
        scored, conceded = (match.hg, match.ag) if at_home else (match.ag, match.hg)
        points = 3.0 if scored > conceded else 1.0 if scored == conceded else 0.0
        opponent = match.away if at_home else match.home
        opponent_stats = stats.get(opponent.casefold())
        opponent_ppg = (opponent_stats["points"] / opponent_stats["games"]
                        if opponent_stats and opponent_stats["games"] > 0 else league_ppg)
        opponent_adjustment = min(1.15, max(0.85, 0.85 + 0.15 * opponent_ppg / league_ppg))
        weighted_points += points * opponent_adjustment * weight
        weight_total += weight
        goal_diff += scored - conceded
        form.append("V" if points == 3.0 else "E" if points == 1.0 else "D")

    average_points = weighted_points / weight_total
    average_goal_diff = goal_diff / len(recent)
    factor = min(1.15, max(0.85, 1.0 + (average_points - 1.5) * 0.07
                            + average_goal_diff * 0.035))
    return factor, "".join(form)


def head_to_head_factor(home: str, away: str, history: list[Match]) -> float:
    meetings = [m for m in history if
                (m.home.casefold() == home.casefold() and m.away.casefold() == away.casefold())
                or (m.home.casefold() == away.casefold() and m.away.casefold() == home.casefold())][-10:]
    if not meetings:
        return 1.0
    weighted_balance = total_weight = 0.0
    for index, match in enumerate(meetings):
        weight = float(index + 1)
        home_was_home = match.home.casefold() == home.casefold()
        home_goals, away_goals = (match.hg, match.ag) if home_was_home else (match.ag, match.hg)
        result = 1.0 if home_goals > away_goals else -1.0 if home_goals < away_goals else 0.0
        weighted_balance += result * weight
        total_weight += weight
    balance = weighted_balance / total_weight if total_weight else 0.0
    return min(1.035, max(0.965, 1.0 + balance * 0.035))


def poisson_engine(match: Match, history: list[Match]) -> tuple[tuple[float, float, float], float, float]:
    league_home = sum(m.hg for m in history) / len(history) if history else 1.35
    league_away = sum(m.ag for m in history) / len(history) if history else 1.10
    league_home, league_away = max(0.2, league_home), max(0.2, league_away)

    home_games = [m for m in history if m.home.casefold() == match.home.casefold()]
    away_games = [m for m in history if m.away.casefold() == match.away.casefold()]

    def weighted_goals(games: list[Match], name: str, scored: bool) -> float:
        recent = games[-8:]
        if not recent:
            return 0.0
        weighted_sum = weight_total = 0.0
        for index, old in enumerate(recent):
            goals = (old.hg if scored else old.ag) if old.home.casefold() == name.casefold() else (old.ag if scored else old.hg)
            weight = float(index + 1)
            weighted_sum += goals * weight
            weight_total += weight
        return weighted_sum / weight_total if weight_total else 0.0

    def smooth(observed: float, reference: float, count: int) -> float:
        reliability = count / (count + 4.0)
        return reference + (observed - reference) * reliability

    home_attack = smooth(weighted_goals(home_games, match.home, True) if home_games else league_home,
                         league_home, len(home_games))
    home_defence = smooth(weighted_goals(home_games, match.home, False) if home_games else league_away,
                           league_away, len(home_games))
    away_attack = smooth(weighted_goals(away_games, match.away, True) if away_games else league_away,
                         league_away, len(away_games))
    away_defence = smooth(weighted_goals(away_games, match.away, False) if away_games else league_home,
                           league_home, len(away_games))

    attack_home_factor = min(2.5, max(0.35, home_attack / league_home))
    defence_away_factor = min(2.5, max(0.35, away_defence / league_home))
    attack_away_factor = min(2.5, max(0.35, away_attack / league_away))
    defence_home_factor = min(2.5, max(0.35, home_defence / league_away))

    stats = historical_team_stats(history)
    home_form, _ = recent_form(match.home, history, stats)
    away_form, _ = recent_form(match.away, history, stats)
    h2h = head_to_head_factor(match.home, match.away, history)

    expected_home = min(3.8, max(0.15, league_home *
        math.sqrt(attack_home_factor * defence_away_factor) * home_form * h2h))
    expected_away = min(3.8, max(0.15, league_away *
        math.sqrt(attack_away_factor * defence_home_factor) * away_form * (2.0 - h2h)))

    home = draw = away = 0.0
    for hg in range(9):
        for ag in range(9):
            chance = poisson(expected_home, hg) * poisson(expected_away, ag)
            if hg > ag:
                home += chance
            elif hg == ag:
                draw += chance
            else:
                away += chance
    return normalize_probs((home, draw, away)), expected_home, expected_away


def kotlin_round(value: float) -> int:
    # Equivalente a kotlin.math.roundToInt para valores não negativos.
    return math.floor(value + 0.5)


def score_outcome(home_goals: int, away_goals: int) -> int:
    return 0 if home_goals > away_goals else 1 if home_goals == away_goals else 2


def score_based_metrics(rows: list[dict]) -> dict:
    if not rows:
        return {"games": 0, "accuracy": 0.0, "exact_score_rate": 0.0,
                "per_class": {}, "confusion_matrix": [[0, 0, 0] for _ in range(3)]}
    matrix = [[0, 0, 0] for _ in range(3)]
    exact = correct = 0
    for row in rows:
        pred = row["score_outcome"]
        actual = row["actual"]
        matrix[actual][pred] += 1
        correct += int(pred == actual)
        exact += int(row["predicted_home_goals"] == row["actual_home_goals"]
                     and row["predicted_away_goals"] == row["actual_away_goals"])
    per_class = {}
    for i, name in enumerate(CLASSES):
        tp = matrix[i][i]
        fp = sum(matrix[r][i] for r in range(3) if r != i)
        fn = sum(matrix[i][c] for c in range(3) if c != i)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        per_class[name] = {"precision": precision, "recall": recall,
                           "predicted": sum(matrix[r][i] for r in range(3)), "correct": tp}
    return {"games": len(rows), "accuracy": correct / len(rows),
            "exact_score_rate": exact / len(rows), "per_class": per_class,
            "confusion_matrix": matrix}

def predict(match: Match, all_matches: list[Match]) -> dict:
    # Regra conservadora: nenhuma partida da mesma data entra no contexto.
    history = [m for m in all_matches if m.date < match.date]
    elo = elo_prob(match, history)
    pois, expected_home, expected_away = poisson_engine(match, history)
    blend = normalize_probs(tuple((a + b) / 2 for a, b in zip(elo, pois)))
    predicted_home, predicted_away = kotlin_round(expected_home), kotlin_round(expected_away)
    actual = score_outcome(match.hg, match.ag)
    return {
        "actual": actual,
        "elo": elo,
        "poisson": pois,
        "blend": blend,
        "score_outcome": score_outcome(predicted_home, predicted_away),
        "predicted_home_goals": predicted_home,
        "predicted_away_goals": predicted_away,
        "actual_home_goals": match.hg,
        "actual_away_goals": match.ag,
        "expected_home_goals": expected_home,
        "expected_away_goals": expected_away,
        "date": match.date,
        "home": match.home,
        "away": match.away,
    }


def argmax(values: tuple[float, float, float]) -> int:
    return max(range(3), key=lambda i: values[i])


def score_metrics(rows: list[dict], model: str, draw_ratio: float | None = None) -> dict:
    if not rows:
        return {"games": 0, "accuracy": 0.0, "brier": 0.0, "macro_f1": 0.0}
    matrix = [[0, 0, 0] for _ in range(3)]
    brier = 0.0
    correct = 0
    for row in rows:
        probs = row[model]
        pred = argmax(probs)
        if draw_ratio is not None and probs[1] >= draw_ratio * max(probs[0], probs[2]):
            pred = 1
        actual = row["actual"]
        matrix[actual][pred] += 1
        correct += int(pred == actual)
        brier += sum((probs[i] - (1.0 if i == actual else 0.0)) ** 2 for i in range(3))
    f1s, per_class = [], {}
    for i, name in enumerate(CLASSES):
        tp = matrix[i][i]
        fp = sum(matrix[r][i] for r in range(3) if r != i)
        fn = sum(matrix[i][c] for c in range(3) if c != i)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1s.append(f1)
        per_class[name] = {"precision": precision, "recall": recall, "f1": f1,
                           "predicted": sum(matrix[r][i] for r in range(3)),
                           "correct": tp}
    return {
        "games": len(rows),
        "accuracy": correct / len(rows),
        "brier": brier / len(rows),
        "macro_f1": sum(f1s) / 3,
        "per_class": per_class,
        "confusion_matrix": matrix,
    }


def best_draw_threshold(train: list[dict]) -> tuple[float, dict]:
    candidates = [round(x / 100, 2) for x in range(50, 101, 5)]
    results = [(score_metrics(train, "blend", t)["macro_f1"], t,
                score_metrics(train, "blend", t)) for t in candidates]
    # Empate de métricas: prefere o limiar mais alto, que é menos agressivo.
    best = max(results, key=lambda item: (item[0], item[1]))
    return best[1], best[2]


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def main() -> None:
    raw = fetch_matches()
    matches = normalize(raw)
    if len(matches) < 100:
        raise ValueError(f"Base insuficiente para laboratório: {len(matches)} jogos válidos em {SEASON}.")
    dates = sorted({match.date for match in matches})
    split_date_index = int(len(dates) * 0.70)
    if split_date_index <= 1 or len(dates) - split_date_index < 2:
        raise ValueError("Amostra insuficiente para validação temporal 70/30.")
    split_date = dates[split_date_index]

    predictions = [predict(match, matches) for match in matches]
    # O corte é por data, nunca por linha: partidas do mesmo dia não
    # podem cair simultaneamente em treino e teste.
    train = [row for row in predictions if row["date"] < split_date]
    test = [row for row in predictions if row["date"] >= split_date]
    if len(train) < 20 or len(test) < 20:
        raise ValueError("Amostra insuficiente após separar treino/teste por data.")
    threshold, train_threshold_metrics = best_draw_threshold(train)

    models = {}
    for model in ("elo", "poisson", "blend"):
        models[model] = {
            "train": score_metrics(train, model),
            "test": score_metrics(test, model),
        }
    # Reprodução do fluxo oficial: probabilidades 1X2 do Elo e placar
    # exibido calculado pelo Poisson contextual, com forma e H2H.
    models["motor_kotlin_score"] = {
        "train": score_based_metrics(train),
        "test": score_based_metrics(test),
        "result_source": "placar exibido arredondado, igual à regra de avaliação do app",
    }
    models["blend_contextual_draw"] = {
        "train": train_threshold_metrics,
        "test": score_metrics(test, "blend", threshold),
        "draw_ratio_threshold": threshold,
    }

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "season": SEASON,
        "source": "Football-Data API via Worker fd-history",
        "historical_games": len(matches),
        "train_games": len(train),
        "test_games": len(test),
        "test_start_date": split_date,
        "same_day_excluded": True,
        "train_test_split_by_date": True,
        "official_engine_changed": False,
        "kotlin_engine_parity": True,
        "models": models,
        "notes": [
            "Laboratório independente; nenhum resultado altera o motor oficial.",
            "Cada previsão usa somente partidas finalizadas em datas anteriores à partida-alvo.",
            "Ajuste do detector contextual é escolhido exclusivamente no bloco de treino.",
            "Brier é a soma dos erros quadráticos das três classes, média por partida; menor é melhor.",
            "O bloco motor_kotlin_score reproduz o Poisson contextual do Kotlin (forma recente, força do adversário e H2H) e arredondamento do placar.",
            "As probabilidades 1X2 oficiais do app continuam sendo Elo; o critério de acerto do histórico segue o placar exibido.",
            "O laboratório deriva a força dos adversários somente dos jogos anteriores à partida, sem usar a tabela atual."
        ],
    }
    OUTPUT.mkdir(exist_ok=True)
    (OUTPUT / "resultado.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        f"# Laboratório do motor 2.0 | Brasileirão {SEASON}",
        "",
        f"- Gerado em: {report['generated_at']}",
        f"- Jogos válidos: **{len(matches)}**",
        f"- Treino cronológico (70%): **{len(train)}**",
        f"- Teste futuro (aprox. 30%): **{len(test)}** (a partir de {split_date})",
        "- Partidas do mesmo dia excluídas do contexto para evitar vazamento.",
        "- Motor oficial preservado: **sim**.",
        "",
        "## Comparação no teste futuro",
        "",
        "| Modelo | Acerto | Macro-F1 | Brier | Empates previstos | Empates certos |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for key, label in (("elo", "Elo"), ("poisson", "Poisson"), ("blend", "Combinação 50/50")):
        m = models[key]["test"]
        d = m["per_class"]["empate"]
        lines.append(f"| {label} | {pct(m['accuracy'])} ({m['games'] and round(m['accuracy']*m['games'])}/{m['games']}) | {pct(m['macro_f1'])} | {m['brier']:.4f} | {d['predicted']} | {d['correct']} |")
    kotlin_score = models["motor_kotlin_score"]["test"]
    lines += [
        f"| Motor Kotlin (placar contextual) | {pct(kotlin_score['accuracy'])} ({round(kotlin_score['accuracy']*kotlin_score['games'])}/{kotlin_score['games']}) | n/a | n/a | n/a | n/a |",
        "",
        f"Placar exato do motor contextual no teste: **{pct(kotlin_score['exact_score_rate'])}**.",
        "",
    ]
    contextual = models["blend_contextual_draw"]["test"]
    d = contextual["per_class"]["empate"]
    lines += [
        f"| Combinação + empate contextual ({threshold:.2f}) | {pct(contextual['accuracy'])} ({round(contextual['accuracy']*contextual['games'])}/{contextual['games']}) | {pct(contextual['macro_f1'])} | {contextual['brier']:.4f} | {d['predicted']} | {d['correct']} |",
        "",
        "## Métricas por classe no teste",
        "",
    ]
    for key, label in (("blend", "Combinação 50/50"), ("blend_contextual_draw", "Combinação + empate contextual")):
        metrics = models[key]["test"]
        lines += [f"### {label}", "", "| Resultado | Precisão | Recall | F1 | Previsões | Acertos |", "|---|---:|---:|---:|---:|---:|"]
        for name in CLASSES:
            c = metrics["per_class"][name]
            lines.append(f"| {name.title()} | {pct(c['precision'])} | {pct(c['recall'])} | {pct(c['f1'])} | {c['predicted']} | {c['correct']} |")
        lines.append("")
    lines += [
        "## Decisão automática",
        "",
        "Este relatório é experimental. Nenhum modelo é promovido automaticamente. A substituição do motor oficial exige repetição em outras janelas/temporadas e validação no mesmo conjunto de jogos usado pelo aplicativo.",
        "",
        "## Limitações",
        "",
        *[f"- {note}" for note in report["notes"]],
        "",
    ]
    (OUTPUT / "relatorio.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:25]))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERRO NO LABORATÓRIO: {exc}", file=sys.stderr)
        raise
