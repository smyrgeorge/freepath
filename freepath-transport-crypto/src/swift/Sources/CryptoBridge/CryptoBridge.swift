import CryptoKit
import Foundation

@objc(CryptoBridge) public class CryptoBridge: NSObject {

    // MARK: - X25519

    @objc public static func generateX25519KeyPair() -> NSArray {
        let privateKey = Curve25519.KeyAgreement.PrivateKey()
        let publicKey = privateKey.publicKey
        return [
            privateKey.rawRepresentation as NSData,
            publicKey.rawRepresentation as NSData,
        ] as NSArray
    }

    @objc public static func x25519DH(
        privateKey: NSData,
        publicKey: NSData,
        error: NSErrorPointer
    ) -> NSData? {
        do {
            let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey as Data)
            let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey as Data)
            let shared = try priv.sharedSecretFromKeyAgreement(with: pub)
            return shared.withUnsafeBytes { NSData(bytes: $0.baseAddress!, length: $0.count) }
        } catch let err {
            error?.pointee = err as NSError
            return nil
        }
    }

    // MARK: - HKDF

    @objc public static func hkdfSha256(
        ikm: NSData,
        salt: NSData,
        info: NSData,
        outputLen: Int
    ) -> NSData {
        let key = SymmetricKey(data: ikm as Data)
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: key,
            salt: salt as Data,
            info: info as Data,
            outputByteCount: outputLen
        )
        return derived.withUnsafeBytes { NSData(bytes: $0.baseAddress!, length: $0.count) }
    }

    // MARK: - ChaCha20Poly1305

    @objc public static func chachaEncrypt(
        key: NSData,
        nonce: NSData,
        plaintext: NSData,
        aad: NSData,
        error: NSErrorPointer
    ) -> NSData? {
        precondition(key.length == 32, "ChaCha20 key must be 32 bytes")
        precondition(nonce.length == 12, "ChaCha20 nonce must be 12 bytes")
        do {
            let symmetricKey = SymmetricKey(data: key as Data)
            let nonceObj = try ChaChaPoly.Nonce(data: nonce as Data)
            let sealed = try ChaChaPoly.seal(
                plaintext as Data,
                using: symmetricKey,
                nonce: nonceObj,
                authenticating: aad as Data
            )
            return sealed.combined as NSData
        } catch let err {
            error?.pointee = err as NSError
            return nil
        }
    }

    @objc public static func chachaDecrypt(
        key: NSData,
        ciphertext: NSData,
        aad: NSData,
        error: NSErrorPointer
    ) -> NSData? {
        precondition(key.length == 32, "ChaCha20 key must be 32 bytes")
        do {
            let symmetricKey = SymmetricKey(data: key as Data)
            let sealedBox = try ChaChaPoly.SealedBox(combined: ciphertext as Data)
            let plaintext = try ChaChaPoly.open(sealedBox, using: symmetricKey, authenticating: aad as Data)
            return plaintext as NSData
        } catch let err {
            error?.pointee = err as NSError
            return nil
        }
    }

    // MARK: - Ed25519

    @objc public static func generateEd25519KeyPair() -> NSArray {
        let privateKey = Curve25519.Signing.PrivateKey()
        let publicKey = privateKey.publicKey
        return [
            privateKey.rawRepresentation as NSData,
            publicKey.rawRepresentation as NSData,
        ] as NSArray
    }

    @objc public static func sign(
        privateKey: NSData,
        message: NSData,
        error: NSErrorPointer
    ) -> NSData? {
        do {
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKey as Data)
            return try key.signature(for: message as Data) as NSData
        } catch let err {
            error?.pointee = err as NSError
            return nil
        }
    }

    @objc public static func verify(
        publicKey: NSData,
        message: NSData,
        signature: NSData
    ) -> Bool {
        guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey as Data) else {
            return false
        }
        return pub.isValidSignature(signature as Data, for: message as Data)
    }

    @objc public static func randomBytes(_ size: Int) -> NSData {
        guard size > 0 else { return NSData() }
        var data = Data(count: size)
        let ok = data.withUnsafeMutableBytes { ptr -> Bool in
            SecRandomCopyBytes(kSecRandomDefault, size, ptr.baseAddress!) == errSecSuccess
        }
        precondition(ok, "SecRandomCopyBytes failed")
        return data as NSData
    }
}
