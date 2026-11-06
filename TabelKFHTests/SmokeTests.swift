import XCTest
@testable import TabelKFH

final class SmokeTests: XCTestCase {
    func testAppBundleLoads() {
        XCTAssertEqual(Bundle(for: SmokeTests.self).bundleIdentifier, "ru.zubakhin.tabelkfh.tests")
        XCTAssertNotNil(Bundle.main.bundleIdentifier)
    }
}
