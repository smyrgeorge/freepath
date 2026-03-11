import Cwasm3
import Foundation

@objc(Wasm3Bridge) public class Wasm3Bridge: NSObject {

    private let env: OpaquePointer
    private let runtime: OpaquePointer
    private var wasmData: Data  // keeps bytes alive — wasm3 requires this
    private let lock = NSLock()

    @objc public init(wasmBytes: Data) throws {
        guard let e = m3_NewEnvironment() else { throw Wasm3BridgeError.initFailed }
        env = e
        guard let r = m3_NewRuntime(env, 512 * 1024, nil) else {
            m3_FreeEnvironment(env)
            throw Wasm3BridgeError.initFailed
        }
        runtime = r
        wasmData = wasmBytes
        super.init()

        var failed = false
        wasmData.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            let ptr = buf.bindMemory(to: UInt8.self).baseAddress!
            var mod: IM3Module? = nil
            if m3_ParseModule(env, &mod, ptr, UInt32(wasmData.count)) != nil {
                failed = true
                return
            }
            guard let m = mod else {
                failed = true
                return
            }
            if m3_LoadModule(runtime, m) != nil { failed = true }
        }
        if failed {
            m3_FreeRuntime(runtime)
            m3_FreeEnvironment(env)
            throw Wasm3BridgeError.loadFailed
        }
    }

    deinit {
        m3_FreeRuntime(runtime)
        m3_FreeEnvironment(env)
    }

    @objc public func call(_ function: String, input: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        guard let inputBytes = input.data(using: .utf8) else { return "" }
        let inputLen = Int32(inputBytes.count)

        var allocFn: IM3Function? = nil
        var deallocFn: IM3Function? = nil
        var fn: IM3Function? = nil
        var resultPtrFn: IM3Function? = nil
        guard m3_FindFunction(&allocFn, runtime, "wasm_alloc") == nil,
            m3_FindFunction(&deallocFn, runtime, "wasm_dealloc") == nil,
            m3_FindFunction(&fn, runtime, function) == nil,
            m3_FindFunction(&resultPtrFn, runtime, "wasm_result_ptr") == nil,
            let af = allocFn, let df = deallocFn,
            let f = fn, let rpf = resultPtrFn
        else { return "" }

        // 1. wasm_alloc(inputLen) -> ptr
        //    Get result IMMEDIATELY before any other call.
        var ptr: UInt32 = 0
        withUnsafePointer(to: inputLen) { lp in
            var args: [UnsafeRawPointer?] = [UnsafeRawPointer(lp)]
            args.withUnsafeMutableBufferPointer { _ = m3_Call(af, 1, $0.baseAddress) }
        }
        withUnsafeMutablePointer(to: &ptr) { p in
            var args: [UnsafeRawPointer?] = [UnsafeRawPointer(p)]
            args.withUnsafeMutableBufferPointer { _ = m3_GetResults(af, 1, $0.baseAddress) }
        }

        // 2. Write input bytes to WASM linear memory
        var memSize: UInt32 = 0
        if let mem = m3_GetMemory(runtime, &memSize, 0),
            Int(ptr) + inputBytes.count <= Int(memSize)
        {
            inputBytes.withUnsafeBytes { _ = memcpy(mem.advanced(by: Int(ptr)), $0.baseAddress!, inputBytes.count) }
        }

        // 3. fn(ptr, inputLen) -> outputLen
        //    Get result IMMEDIATELY before any other call.
        var outputLen: UInt32 = 0
        withUnsafePointer(to: ptr) { pp in
            withUnsafePointer(to: inputLen) { lp in
                var args: [UnsafeRawPointer?] = [UnsafeRawPointer(pp), UnsafeRawPointer(lp)]
                args.withUnsafeMutableBufferPointer { _ = m3_Call(f, 2, $0.baseAddress) }
            }
        }
        withUnsafeMutablePointer(to: &outputLen) { p in
            var args: [UnsafeRawPointer?] = [UnsafeRawPointer(p)]
            args.withUnsafeMutableBufferPointer { _ = m3_GetResults(f, 1, $0.baseAddress) }
        }

        // 4. wasm_result_ptr() -> outputPtr
        //    Get result IMMEDIATELY before dealloc call.
        var outputPtr: UInt32 = 0
        if m3_Call(rpf, 0, nil) == nil {
            withUnsafeMutablePointer(to: &outputPtr) { p in
                var args: [UnsafeRawPointer?] = [UnsafeRawPointer(p)]
                args.withUnsafeMutableBufferPointer { _ = m3_GetResults(rpf, 1, $0.baseAddress) }
            }
        }

        // 5. Read output string from WASM memory
        var result = ""
        memSize = 0
        if let mem = m3_GetMemory(runtime, &memSize, 0), outputLen > 0,
            Int(outputPtr) + Int(outputLen) <= Int(memSize)
        {
            let data = Data(bytes: mem.advanced(by: Int(outputPtr)), count: Int(outputLen))
            result = String(data: data, encoding: .utf8) ?? ""
        }

        // 6. wasm_dealloc(ptr, inputLen)
        withUnsafePointer(to: ptr) { pp in
            withUnsafePointer(to: inputLen) { lp in
                var args: [UnsafeRawPointer?] = [UnsafeRawPointer(pp), UnsafeRawPointer(lp)]
                args.withUnsafeMutableBufferPointer { _ = m3_Call(df, 2, $0.baseAddress) }
            }
        }

        return result
    }
}

@objc public enum Wasm3BridgeError: Int, Error {
    case initFailed = 0
    case loadFailed = 1
}
