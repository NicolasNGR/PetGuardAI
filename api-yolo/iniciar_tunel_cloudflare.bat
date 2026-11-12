@echo off
setlocal
cd /d "%~dp0"

where cloudflared >nul 2>nul
if errorlevel 1 (
    echo cloudflared nao encontrado.
    echo Instale o cloudflared oficial da Cloudflare e abra este arquivo novamente.
    pause
    exit /b 1
)

curl --silent --fail http://127.0.0.1:8001/health >nul
if errorlevel 1 (
    echo A API PetGuard nao esta respondendo na porta 8001.
    echo Inicie iniciar_api_local.bat primeiro.
    pause
    exit /b 1
)

echo Criando tunel HTTPS temporario para a API PetGuard.
echo Copie o endereco https://*.trycloudflare.com mostrado abaixo e cadastre-o no app como camera remota.
echo A API continua exigindo autenticacao Firebase. Para encerrar o tunel, pressione Ctrl+C.
cloudflared tunnel --url http://127.0.0.1:8001 --no-autoupdate
pause
