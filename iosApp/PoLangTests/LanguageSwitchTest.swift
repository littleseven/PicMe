import XCTest
@testable import PoLang

final class LanguageSwitchTest: XCTestCase {

    func testLprojBundlesExist() {
        XCTAssertNotNil(Bundle.main.path(forResource: "en", ofType: "lproj"))
        XCTAssertNotNil(Bundle.main.path(forResource: "zh-Hans", ofType: "lproj"))
    }

    func testTranslateChinese() {
        let lm = LanguageManager.shared
        lm.currentLanguage = "chinese_simplified"
        let val = L("Settings")
        print("📋 L(Settings) Chinese: '\(val)'")
        XCTAssertEqual(val, "设置")
    }

    func testTranslateEnglish() {
        let lm = LanguageManager.shared
        lm.currentLanguage = "english"
        let val = L("Settings")
        print("📋 L(Settings) English: '\(val)'")
        XCTAssertEqual(val, "Settings")
    }

    func testChineseThenEnglish() {
        let lm = LanguageManager.shared
        lm.currentLanguage = "chinese_simplified"
        let zh = L("Settings")
        print("📋 After Chinese: '\(zh)'")
        XCTAssertEqual(zh, "设置")

        lm.currentLanguage = "english"
        let en = L("Settings")
        print("📋 After English: '\(en)'")
        XCTAssertEqual(en, "Settings")
    }

    func testSystemLanguage() {
        let lm = LanguageManager.shared
        lm.currentLanguage = "system"
        // 不应该 crash
        let _ = L("Settings")
    }
}
