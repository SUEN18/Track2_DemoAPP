cmake_minimum_required(VERSION 3.22.1)

project("action_detection_app")

# QNN include path
set(QNN_INCLUDE_PATH "${QNN_SDK_ROOT_PATH}/include/QNN")
set(QNN_WRAPPER_DIR "${QNN_SDK_ROOT_PATH}/share/QNN/converter/jni")
include_directories(${QNN_INCLUDE_PATH})
include_directories(${QNN_WRAPPER_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_library(${CMAKE_PROJECT_NAME} SHARED
        action_detection_engine.cpp
        qnn_helper.cpp
        ${QNN_WRAPPER_DIR}/QnnModel.cpp
        ${QNN_WRAPPER_DIR}/QnnWrapperUtils.cpp
        ${QNN_WRAPPER_DIR}/linux/QnnModelPal.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        jnigraphics)
