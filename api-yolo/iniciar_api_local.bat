@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo Ambiente Python nao encontrado. Instale as dependencias primeiro.
    pause
    exit /b 1
)

echo Iniciando PetGuard AI na porta 8001...
echo Diagnostico local: http://127.0.0.1:8001/health
echo O video e as demais rotas exigem autenticacao pelo app PetGuard.
echo Para parar a API, pressione Ctrl+C nesta janela.
".venv\Scripts\python.exe" -m uvicorn main:app --host 0.0.0.0 --port 8001 --no-access-log
pause
