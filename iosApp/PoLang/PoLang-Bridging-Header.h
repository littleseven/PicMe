//
//  PoLang-Bridging-Header.h
//  Swift ↔ Objective-C/C++ 桥接头。
//  在此 import 需要暴露给 Swift 的 ObjC 接口头（实现留在 .mm，按 ObjC++ 编译）。
//

#ifndef PoLang_Bridging_Header_h
#define PoLang_Bridging_Header_h

#import "Features/Camera/Beauty/MNN/MnnFaceDetectorBridge.h"
#import "Features/Camera/Beauty/MNN/MnnFaceEmbedderBridge.h"

#endif /* PoLang_Bridging_Header_h */
