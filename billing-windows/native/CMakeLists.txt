cmake_minimum_required(VERSION 3.20)
project(WindowsStoreBridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The C API header shared with the StoreKit bridge
set(BRIDGE_HEADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../billing-native-bridge/native" CACHE PATH "billing_bridge.h")

add_library(WindowsStoreBridge SHARED WindowsStoreBridge.cpp)
target_include_directories(WindowsStoreBridge PRIVATE "${BRIDGE_HEADER_DIR}")
target_compile_definitions(WindowsStoreBridge PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_compile_options(WindowsStoreBridge PRIVATE /W4 /WX /EHsc /permissive- /utf-8 /bigobj)
target_link_libraries(WindowsStoreBridge PRIVATE WindowsApp.lib ole32.lib)

# Link the C++ runtime statically so apps don't need the Visual C++ redistributable
set_property(TARGET WindowsStoreBridge PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
