#include "qnn_helper.hpp"
#include "HTP/QnnHtpDevice.h"
#include "QnnWrapperUtils.hpp"
#include "QnnModel.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <fstream>
#include <vector>
#include <sstream>
#include <string_view>
#include <algorithm>
#include <cstdlib>

#define TAG "QnnHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(
        const QnnInterface_t***,
        uint32_t*);

typedef Qnn_ErrorHandle_t (*QnnSystemInterfaceGetProvidersFn_t)(
        const QnnSystemInterface_t***,
        uint32_t*);

static void qnnLogCallback(const char* fmt, QnnLog_Level_t level, uint64_t timestamp, va_list args) {
    int androidLevel = ANDROID_LOG_INFO;
    switch(level) {
        case QNN_LOG_LEVEL_ERROR: androidLevel = ANDROID_LOG_ERROR; break;
        case QNN_LOG_LEVEL_WARN:  androidLevel = ANDROID_LOG_WARN;  break;
        case QNN_LOG_LEVEL_INFO:  androidLevel = ANDROID_LOG_INFO;  break;
        case QNN_LOG_LEVEL_VERBOSE:
        case QNN_LOG_LEVEL_DEBUG: androidLevel = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_vprint(androidLevel, "QnnBackend", fmt, args);
}

QnnHelper::QnnHelper() {
    memset(&m_qnnInterface, 0, sizeof(m_qnnInterface));
    memset(&m_systemInterface, 0, sizeof(m_systemInterface));
}

QnnHelper::~QnnHelper() {

    if (m_context &&
        m_qnnInterface.QNN_INTERFACE_VER_NAME.contextFree) {
        m_qnnInterface.QNN_INTERFACE_VER_NAME.contextFree(m_context, nullptr);
    }

    if (m_device &&
        m_qnnInterface.QNN_INTERFACE_VER_NAME.deviceFree) {
        m_qnnInterface.QNN_INTERFACE_VER_NAME.deviceFree(m_device);
    }

    if (m_backend &&
        m_qnnInterface.QNN_INTERFACE_VER_NAME.backendFree) {
        m_qnnInterface.QNN_INTERFACE_VER_NAME.backendFree(m_backend);
    }

    if (m_logHandle &&
        m_qnnInterface.QNN_INTERFACE_VER_NAME.logFree) {
        m_qnnInterface.QNN_INTERFACE_VER_NAME.logFree(m_logHandle);
    }

    if (m_backendHandle) {
        dlclose(m_backendHandle);
    }

    if (m_systemHandle) {
        dlclose(m_systemHandle);
    }

    if (m_modelDlcHandle) {
        dlclose(m_modelDlcHandle);
    }
}

bool QnnHelper::init(const std::string& backendPath, const std::string& systemLibPath, const std::string& htpConfigPath) {

    // 1. Load Backend
    m_backendHandle = dlopen(backendPath.c_str(), RTLD_NOW);
    if (!m_backendHandle) {
        LOGE("Failed to load backend: %s", dlerror());
        return false;
    }

    auto getProviders =
            (QnnInterfaceGetProvidersFn_t)dlsym(
                    m_backendHandle,
                    "QnnInterface_getProviders");

    if (!getProviders) {
        LOGE("Failed to find QnnInterface_getProviders");
        return false;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t numProviders = 0;

    if (getProviders(&providers, &numProviders) != QNN_SUCCESS ||
        numProviders == 0) {
        LOGE("Failed to get QNN providers");
        return false;
    }
    m_qnnInterface = *providers[0];

    // 2. Load System Lib (Optional, used for metadata)
    if (!systemLibPath.empty()) {
        m_systemHandle = dlopen(systemLibPath.c_str(), RTLD_NOW);
        if (m_systemHandle) {
            auto getSystemProviders =
                    (QnnSystemInterfaceGetProvidersFn_t)dlsym(
                            m_systemHandle,
                            "QnnSystemInterface_getProviders");
            if (getSystemProviders) {
                const QnnSystemInterface_t** sysProviders = nullptr;
                uint32_t numSysProviders = 0;
                if (getSystemProviders(&sysProviders, &numSysProviders) == QNN_SUCCESS && numSysProviders > 0) {
                    m_systemInterface = *sysProviders[0];
                    LOGI("Loaded QNN System Interface");
                }
            }
        }

        // Also load ModelDlc loader from the same directory
        std::string libDir = systemLibPath.substr(0, systemLibPath.find_last_of('/'));
        std::string dlcLoaderPath = libDir + "/libQnnModelDlc.so";
        m_modelDlcHandle = dlopen(dlcLoaderPath.c_str(), RTLD_NOW);
        if (m_modelDlcHandle) {
            m_composeGraphsFromDlcFn = (QnnModel_composeGraphsFromDlcFn_t)dlsym(m_modelDlcHandle, "QnnModel_composeGraphsFromDlc");
            if (m_composeGraphsFromDlcFn) {
                LOGI("Loaded QnnModel_composeGraphsFromDlc from %s", dlcLoaderPath.c_str());
            } else {
                LOGE("Failed to find QnnModel_composeGraphsFromDlc in %s", dlcLoaderPath.c_str());
            }
        } else {
            LOGE("Failed to load libQnnModelDlc.so from %s: %s", dlcLoaderPath.c_str(), dlerror());
        }
    }

    // 3. Set up logging (Tutorial Step 3)
    if (m_qnnInterface.QNN_INTERFACE_VER_NAME.logCreate) {
        if (m_qnnInterface.QNN_INTERFACE_VER_NAME.logCreate(qnnLogCallback, QNN_LOG_LEVEL_INFO, &m_logHandle) != QNN_SUCCESS) {
            LOGE("logCreate failed");
        }
    }

    // 4. Initialize backend (Tutorial Step 4)
    if (m_qnnInterface.QNN_INTERFACE_VER_NAME.backendCreate(
            m_logHandle, nullptr, &m_backend) != QNN_SUCCESS) {
        LOGE("backendCreate failed");
        return false;
    }

    // 5. Create device (Tutorial Step 6)
    const QnnDevice_Config_t* devConfigs[] = {nullptr, nullptr};
    QnnDevice_Config_t socConfig;
    QnnHtpDevice_CustomConfig_t htpSocConfig;

    if (backendPath.find("libQnnHtp.so") != std::string::npos) {
        htpSocConfig.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
        htpSocConfig.socModel = 69; // Try 8 Elite (69) first
        socConfig.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
        socConfig.customConfig = &htpSocConfig;
        devConfigs[0] = &socConfig;
        LOGI("Trying HTP SoC Model 69");
    }

    Qnn_ErrorHandle_t devErr =
            m_qnnInterface.QNN_INTERFACE_VER_NAME.deviceCreate(
                    m_logHandle, devConfigs[0] ? devConfigs : nullptr, &m_device);

    if (devErr != QNN_SUCCESS && htpSocConfig.socModel == 69) {
        LOGI("HTP SoC Model 69 failed, trying 57 (8 Gen 3)");
        htpSocConfig.socModel = 57;
        devErr = m_qnnInterface.QNN_INTERFACE_VER_NAME.deviceCreate(
                    m_logHandle, devConfigs, &m_device);
    }

    if (devErr != QNN_SUCCESS) {
        LOGE("deviceCreate failed: 0x%X", static_cast<uint32_t>(devErr));
        m_device = nullptr;
    } else {
        LOGI("Device created successfully");
    }

    LOGI("QNN initialized successfully following tutorial workflow");
    return true;
}

bool QnnHelper::loadModel(
        const std::string& modelPath,
        bool isTextModel,
        const std::string& htpConfigPath) {

    // 1. Create Context (Tutorial Step 8)
    if (m_qnnInterface.QNN_INTERFACE_VER_NAME.contextCreate(
            m_backend, m_device, nullptr, &m_context) != QNN_SUCCESS) {
        LOGE("contextCreate failed");
        return false;
    }

    // 2. Compose Graphs from DLC (Modern QNN DLC loading)
    if (!m_composeGraphsFromDlcFn) {
        LOGE("m_composeGraphsFromDlcFn is null, cannot load DLC");
        return false;
    }

    qnn_wrapper_api::GraphInfo_t** graphsInfo = nullptr;
    uint32_t numGraphs = 0;

    auto modelStatus = m_composeGraphsFromDlcFn(
            m_backend,
            m_qnnInterface.QNN_INTERFACE_VER_NAME,
            m_context,
            nullptr, // graphsConfigInfo
            modelPath.c_str(),
            0, // numGraphsConfigInfo
            &graphsInfo,
            &numGraphs,
            false, // debug
            qnnLogCallback,
            QNN_LOG_LEVEL_INFO);

    if (modelStatus != qnn_wrapper_api::MODEL_NO_ERROR || numGraphs == 0) {
        LOGE("QnnModel_composeGraphsFromDlc failed: %d", (int)modelStatus);
        return false;
    }

    // 3. Finalize Graphs (Tutorial Step 11)
    m_graph = graphsInfo[0]->graph;
    m_graphName = graphsInfo[0]->graphName;

    if (m_qnnInterface.QNN_INTERFACE_VER_NAME.graphFinalize(m_graph, nullptr, nullptr) != QNN_SUCCESS) {
        LOGE("graphFinalize failed for %s", m_graphName.c_str());
        return false;
    }

    LOGI("Loaded and finalized graph: %s from %s", m_graphName.c_str(), modelPath.c_str());

    // Extract dynamic names and IDs from composed graph metadata
    if (graphsInfo[0]->numInputTensors > 0) {
        m_inputName = QNN_TENSOR_GET_NAME(graphsInfo[0]->inputTensors[0]);
        m_inputId = QNN_TENSOR_GET_ID(graphsInfo[0]->inputTensors[0]);
        LOGI("Input name: %s, ID: %u", m_inputName.c_str(), m_inputId);
    }
    if (graphsInfo[0]->numOutputTensors > 0) {
        m_outputName = QNN_TENSOR_GET_NAME(graphsInfo[0]->outputTensors[0]);
        m_outputId = QNN_TENSOR_GET_ID(graphsInfo[0]->outputTensors[0]);
        LOGI("Output name: %s, ID: %u", m_outputName.c_str(), m_outputId);

        // Get output size
        m_outputSize = 1;
        uint32_t rank = 0;
        uint32_t* dims = nullptr;
        if (graphsInfo[0]->outputTensors[0].version == QNN_TENSOR_VERSION_1) {
            rank = graphsInfo[0]->outputTensors[0].v1.rank;
            dims = graphsInfo[0]->outputTensors[0].v1.dimensions;
        } else if (graphsInfo[0]->outputTensors[0].version == QNN_TENSOR_VERSION_2) {
            rank = graphsInfo[0]->outputTensors[0].v2.rank;
            dims = graphsInfo[0]->outputTensors[0].v2.dimensions;
        }

        if (dims) {
            for (uint32_t i = 0; i < rank; i++) {
                m_outputSize *= dims[i];
            }
        }
        LOGI("Detected output size: %u", m_outputSize);
    }

    // Free the wrapper metadata (not the graph itself)
    qnn_wrapper_api::freeGraphsInfo(&graphsInfo, numGraphs);

    return true;
}

bool QnnHelper::execute(
        const std::vector<float>& inputData,
        std::vector<float>& outputData) {

    if (!m_graph) {
        LOGE("Graph is null");
        return false;
    }

    Qnn_Tensor_t inputTensor = QNN_TENSOR_INIT;
    inputTensor.version = QNN_TENSOR_VERSION_1;
    inputTensor.v1.id = m_inputId;
    inputTensor.v1.name = m_inputName.c_str();
    inputTensor.v1.type = QNN_TENSOR_TYPE_APP_WRITE;
    inputTensor.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    inputTensor.v1.memType = QNN_TENSORMEMTYPE_RAW;

    std::vector<uint32_t> inputDims;
    size_t inputSizeInBytes = 0;
    void* alignedInput = nullptr;

    inputTensor.v1.dataType = QNN_DATATYPE_FLOAT_32;
    inputTensor.v1.rank = 5;
    inputDims = {1, 3, 16, 112, 112};
    inputTensor.v1.dimensions = inputDims.data();
    inputSizeInBytes = 1 * 3 * 16 * 112 * 112 * sizeof(float);

    if (posix_memalign(&alignedInput, 64, inputSizeInBytes) != 0) return false;
    memcpy(alignedInput, inputData.data(), std::min(inputSizeInBytes, inputData.size() * sizeof(float)));

    inputTensor.v1.clientBuf.data = alignedInput;
    inputTensor.v1.clientBuf.dataSize = inputSizeInBytes;

    outputData.resize(m_outputSize);
    void* alignedOutput = nullptr;
    size_t outputSizeInBytes = m_outputSize * sizeof(float);
    if (posix_memalign(&alignedOutput, 64, outputSizeInBytes) != 0) {
        free(alignedInput);
        return false;
    }

    Qnn_Tensor_t outputTensor = QNN_TENSOR_INIT;
    outputTensor.version = QNN_TENSOR_VERSION_1;
    outputTensor.v1.id = m_outputId;
    outputTensor.v1.name = m_outputName.c_str();
    outputTensor.v1.type = QNN_TENSOR_TYPE_APP_READ;
    outputTensor.v1.dataType = QNN_DATATYPE_FLOAT_32;
    outputTensor.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
    outputTensor.v1.memType = QNN_TENSORMEMTYPE_RAW;
    outputTensor.v1.rank = 2;
    std::vector<uint32_t> outputDims = {1, m_outputSize};
    outputTensor.v1.dimensions = outputDims.data();

    outputTensor.v1.clientBuf.data = alignedOutput;
    outputTensor.v1.clientBuf.dataSize = outputSizeInBytes;

    Qnn_ErrorHandle_t execErr =
            m_qnnInterface.QNN_INTERFACE_VER_NAME.graphExecute(
                    m_graph,
                    &inputTensor,
                    1,
                    &outputTensor,
                    1,
                    nullptr,
                    nullptr);

    if (execErr == QNN_SUCCESS) {
        memcpy(outputData.data(), alignedOutput, outputSizeInBytes);
    } else {
        LOGE("graphExecute failed: 0x%X", static_cast<uint32_t>(execErr));
    }

    free(alignedInput);
    free(alignedOutput);

    return (execErr == QNN_SUCCESS);
}
