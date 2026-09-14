# Robot Touch Test

Aplicativo Android para validar toques do robô em uma tela real. A primeira
versão apresenta três alvos fixos: uma bola vermelha, uma azul e uma verde.
Um toque completo dentro de uma bola mostra sucesso, vibra o telefone e grava
o alvo, a coordenada normalizada e o horário no armazenamento local do app.

## Abrir e instalar

1. Abra esta pasta no Android Studio.
2. Aceite a instalação do Android SDK API 36 quando o Android Studio solicitar.
3. Conecte um Motorola com depuração USB ativada.
4. Use **Run** para instalar o app.

Pela linha de comando, após configurar o Android SDK:

```bash
./gradlew installDebug
```

## Próximas extensões

- tamanho dos alvos como nível de dificuldade;
- posições aleatórias com semente registrada;
- tela de configurações;
- exportação dos eventos em JSON ou CSV;
- modo de teste controlado por ROS/HTTP, se necessário.
