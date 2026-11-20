# Brasileirão, o Profeta 2027

## Direção do produto

- Nome oficial: **Brasileirão, o Profeta**
- Identificação da edição: **2027**
- Assinatura: **By Nisch**
- Temporada 2026: laboratório de desenvolvimento, validação estatística e testes.
- Temporada 2027: edição destinada ao lançamento público na Google Play.

## Estratégia de código

- A branch `main` continua sendo a linha de desenvolvimento estável do laboratório 2026.
- A branch `develop/2027` isola a preparação da edição 2027.
- Alterações exclusivas de identidade, temporada e publicação de 2027 devem começar nesta branch.
- Correções comprovadamente genéricas podem ser incorporadas às duas linhas, com revisão e validação em cada uma.
- Não alterar a experiência 2026 em produção de testes só para antecipar a identidade 2027.

## Separação dos dados por temporada

A temporada deve ser uma dimensão explícita dos dados, nunca inferida apenas pelo ano atual.

- **Compartilhável:** histórico encerrado de partidas, estatísticas históricas e parâmetros de avaliação do motor, sempre identificados por competição e temporada.
- **Separado por edição:** calendário, partidas da temporada, palpites, resultados dos palpites, classificação, ranking, pontuação e configurações específicas da edição.
- **Identidade do usuário:** autenticação e dados básicos de perfil podem permanecer globais; os dados competitivos devem ser particionados por temporada.
- **Migração:** preservar os registros de 2026. Não apagar nem sobrescrever palpites, ranking ou histórico ao preparar 2027.

## Modelo lógico proposto

Toda entidade competitiva deve carregar identificadores explícitos, por exemplo:

- `competitionId = "brasileirao-serie-a"`
- `season = 2027`
- `matchId` estável para a partida
- `userId` para dados pessoais
- `round` para a rodada

No Firestore, a organização pode usar documentos/coleções por edição, por exemplo `seasons/2027/matches`, `seasons/2027/predictions` e `seasons/2027/leaderboard`. Este é um desenho-alvo; antes de migrar ou gravar dados, conferir o esquema e as consultas atuais para preservar compatibilidade.

## Identidade Android e publicação

- Nome exibido planejado: **Brasileirão, o Profeta 2027**.
- Manter o identificador Android atual durante o desenvolvimento até uma decisão explícita de publicação; o `applicationId` torna-se uma escolha crítica e estável depois que o app é publicado.
- Antes da publicação, preparar um Android App Bundle (.aab) assinado, política de privacidade, declarações da Play Console, classificação indicativa, testes exigidos para a conta e revisão das políticas vigentes.
- A edição 2026 não será enviada como lançamento público.

## Etapas de preparação

1. Auditar telas, modelos, serviços e caminhos atuais de persistência.
2. Definir um contrato único de temporada e competição.
3. Isolar dados competitivos por temporada sem alterar os registros 2026.
4. Preparar identidade visual e textos de 2027.
5. Atualizar SDKs e requisitos da Play Store próximos da janela de lançamento.
6. Testar a edição 2027 com dados de teste e validar build/assinatura.
7. Publicar somente após aprovação do responsável pelo projeto.

## Regra de qualidade

Build verde é condição necessária, não prova suficiente de funcionalidade. Cada mudança deve ter validação funcional e regressão das jornadas existentes. Nenhuma alteração de lançamento deve comprometer o laboratório 2026.
