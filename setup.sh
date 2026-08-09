#!/bin/bash
# ============================================================
#  DesktopBrain 一键部署脚本
#  用法: bash setup.sh [start|stop|build|clean]
#  环境: Git Bash / WSL / Linux
# ============================================================
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[!!]${NC} $1"; }
err()  { echo -e "${RED}[XX]${NC} $1"; }
info() { echo -e "${BLUE}[--]${NC} $1"; }

# ============================================================
# 1. 检查基础环境
# ============================================================
check_prerequisites() {
    echo ""
    echo "============================================"
    echo "  第1步: 检查基础环境"
    echo "============================================"

    # Java 25
    if command -v java &>/dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '"\K[0-9]+')
        if [ "$JAVA_VER" -ge 25 ]; then
            log "Java $JAVA_VER ($(which java))"
        else
            err "需要 Java 25+, 当前: $JAVA_VER"
            exit 1
        fi
    else
        err "未找到 Java，请安装 JDK 25+"
        exit 1
    fi

    # Maven
    if command -v mvn &>/dev/null; then
        MVN_VER=$(mvn -version 2>&1 | head -1 | awk '{print $3}')
        log "Maven $MVN_VER ($(which mvn))"
    else
        err "未找到 Maven"
        exit 1
    fi

    # Docker
    if command -v docker &>/dev/null; then
        if docker info &>/dev/null 2>&1; then
            log "Docker 运行中 ($(docker --version))"
        else
            warn "Docker 已安装但未运行，请启动 Docker Desktop"
        fi
    else
        warn "未找到 Docker（Qdrant + Neo4j 需要）"
    fi

    # Ollama
    if command -v ollama &>/dev/null; then
        if curl -s http://localhost:11434/api/tags &>/dev/null; then
            log "Ollama 运行中"
        else
            warn "Ollama 未运行，请执行: ollama serve"
        fi
    else
        warn "未找到 Ollama（本地模型 qwen2.5:3b 需要）"
    fi
}

# ============================================================
# 2. 启动 Docker 容器 (Qdrant + Neo4j + Home Assistant)
# ============================================================
start_docker() {
    echo ""
    echo "============================================"
    echo "  第2步: 启动 Docker 容器"
    echo "============================================"

    if [ -f "docker-compose.yml" ]; then
        info "docker compose up -d"
        docker compose up -d 2>/dev/null || docker-compose up -d
        sleep 3
        log "容器已启动"
    else
        warn "未找到 docker-compose.yml"
    fi
}

# ============================================================
# 3. 拉取 Ollama 模型
# ============================================================
pull_ollama_models() {
    echo ""
    echo "============================================"
    echo "  第3步: 拉取 Ollama 模型"
    echo "============================================"

    if ! curl -s http://localhost:11434/api/tags &>/dev/null; then
        warn "Ollama 未运行，跳过模型拉取"
        return
    fi

    for model in "qwen2.5:3b" "nomic-embed-text"; do
        info "检查模型: $model"
        if ollama list 2>/dev/null | grep -q "$model"; then
            log "模型已存在: $model"
        else
            info "正在拉取: $model ..."
            ollama pull "$model"
            log "拉取完成: $model"
        fi
    done
}

# ============================================================
# 4. 验证模型文件
# ============================================================
verify_models() {
    echo ""
    echo "============================================"
    echo "  第4步: 验证模型文件"
    echo "============================================"

    # 原生 DLL
    info "检查原生 DLL ..."
    DLL_COUNT=0
    for dll in onnxruntime.dll onnxruntime_providers_shared.dll sherpa-onnx-cxx-api.dll sherpa-onnx-jni.dll; do
        if [ -f "lib/native/$dll" ]; then
            ((DLL_COUNT++))
        else
            err "缺少: lib/native/$dll"
        fi
    done
    if [ "$DLL_COUNT" -eq 4 ]; then
        log "原生 DLL 完整 ($DLL_COUNT/4)"
    fi

    # 检查 DLL 是否在 System32/SysWOW64 冲突
    if [ -f "/c/Windows/System32/onnxruntime.dll" ]; then
        SIZE=$(stat -c%s "/c/Windows/System32/onnxruntime.dll" 2>/dev/null || echo 0)
        if [ "$SIZE" = "2832" ] || [ "$SIZE" = "2512" ]; then
            warn "检测到 System32 下有冲突的 onnxruntime.dll ($SIZE 字节)"
            info "建议重命名为 onnxruntime.dll.bak 以避免 UnsatisfiedLinkError"
        fi
    fi

    # AI 模型
    info "检查 ASR/TTS/VAD 模型 ..."
    MODEL_OK=0

    [ -f "models/vad/silero_vad.onnx" ] && ((MODEL_OK++)) || warn "缺少: models/vad/silero_vad.onnx"
    [ -f "models/speaker-embedding/model.onnx" ] && ((MODEL_OK++)) || warn "缺少: models/speaker-embedding/model.onnx"
    [ -f "models/tts-zh/model.onnx" ] && ((MODEL_OK++)) || warn "缺少: models/tts-zh/model.onnx"
    [ -f "models/asr-offline/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx" ] && ((MODEL_OK++)) || warn "缺少: models/asr-offline/..."

    if [ "$MODEL_OK" -ge 4 ]; then
        log "AI 模型完整 ($MODEL_OK/4)"
    else
        warn "AI 模型不完整 ($MODEL_OK/4)，部分功能可能不可用"
    fi

    # ASR 双语模型（classpath 内的，构建时自动复制）
    ASR_BI_PATH="src/main/resources/models/asr-bilingual"
    if [ -f "$ASR_BI_PATH/encoder-epoch-99-avg-1.int8.onnx" ]; then
        log "ASR 在线模型 (asr-bilingual) 已就绪"
    else
        warn "缺少 ASR 在线模型: $ASR_BI_PATH/"
    fi
}

# ============================================================
# 5. Maven 构建
# ============================================================
build_project() {
    echo ""
    echo "============================================"
    echo "  第5步: Maven 构建"
    echo "============================================"

    info "执行: mvn clean compile -DskipTests"
    mvn clean compile -DskipTests

    log "构建完成"
}

# ============================================================
# 6. 启动应用
# ============================================================
start_app() {
    echo ""
    echo "============================================"
    echo "  第6步: 启动应用"
    echo "============================================"

    info "执行: mvn spring-boot:run"
    echo "============================================"
    echo ""
    mvn spring-boot:run
}

# ============================================================
# 辅助: 停止 Docker 容器
# ============================================================
stop_docker() {
    echo "停止 Docker 容器 ..."
    docker compose down 2>/dev/null || docker-compose down
    log "容器已停止"
}

# ============================================================
# 辅助: 清理构建产物
# ============================================================
clean_project() {
    echo "清理构建产物 ..."
    mvn clean
    log "清理完成"
}

# ============================================================
# 入口
# ============================================================
case "${1:-start}" in
    start)
        check_prerequisites
        verify_models
        start_docker
        pull_ollama_models
        build_project
        start_app
        ;;

    build)
        build_project
        ;;

    stop)
        stop_docker
        ;;

    clean)
        clean_project
        ;;

    check)
        check_prerequisites
        verify_models
        ;;

    *)
        echo "用法: bash setup.sh [start|build|stop|clean|check]"
        echo ""
        echo "  start   完整部署: 检查环境 → 验证模型 → 启动Docker → 拉取Ollama → 构建 → 启动"
        echo "  build   仅 Maven 构建"
        echo "  stop    停止 Docker 容器"
        echo "  clean   清理构建产物"
        echo "  check   仅检查环境 + 模型完整性"
        exit 1
        ;;
esac
