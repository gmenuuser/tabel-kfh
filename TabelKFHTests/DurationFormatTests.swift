import XCTest
@testable import TabelKFH

final class DurationFormatTests: XCTestCase {
    func testDurationText() {
        XCTAssertEqual(durationText(minutes: 450), "7 ч 30 мин")
        XCTAssertEqual(durationText(minutes: 480), "8 ч")
        XCTAssertEqual(durationText(minutes: 45), "45 мин")
        XCTAssertEqual(durationText(minutes: 0), "0 мин")
    }
}
