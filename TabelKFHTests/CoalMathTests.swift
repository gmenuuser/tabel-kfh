import XCTest
@testable import TabelKFH

final class CoalMathTests: XCTestCase {
    // Spec 2: 2 т × 6500 ₽ = 13 000 ₽, and fractional tons.
    func testCoalAmountIsTonsTimesPrice() {
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 2000, pricePerTon: 650_000), 1_300_000)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 1000, pricePerTon: 650_000), 650_000)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 500, pricePerTon: 650_000), 325_000)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 1500, pricePerTon: 650_000), 975_000)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 2500, pricePerTon: 650_000), 1_625_000)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 0, pricePerTon: 650_000), 0)
    }

    func testCoalAmountRoundsHalfUpToKopeck() {
        // 0,001 т × 6500,05 ₽ = 6,50005 ₽ → 6,50 ₽; 0,003 т × 1,50 ₽ = 0,0045 ₽ → 0 коп.
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 1, pricePerTon: 650_005), 650)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 3, pricePerTon: 150), 0)
        // 0,005 т × 1,00 ₽ = 0,005 ₽ → 1 коп. (half up)
        XCTAssertEqual(CoalMath.coalAmount(kilograms: 5, pricePerTon: 100), 1)
    }

    func testParseTons() {
        XCTAssertEqual(CoalMath.parseTons("2"), 2000)
        XCTAssertEqual(CoalMath.parseTons("2,5"), 2500)
        XCTAssertEqual(CoalMath.parseTons("0.5"), 500)
        XCTAssertEqual(CoalMath.parseTons(" 1,25 "), 1250)
        XCTAssertEqual(CoalMath.parseTons("0,125"), 125)
        XCTAssertEqual(CoalMath.parseTons(",5"), 500)
        XCTAssertEqual(CoalMath.parseTons("3,"), 3000)
        XCTAssertNil(CoalMath.parseTons(""))
        XCTAssertNil(CoalMath.parseTons("-1"))
        XCTAssertNil(CoalMath.parseTons("1,2345"))
        XCTAssertNil(CoalMath.parseTons("два"))
        XCTAssertNil(CoalMath.parseTons("1,2,3"))
        XCTAssertNil(CoalMath.parseTons(","))
    }

    func testParseRubles() {
        XCTAssertEqual(CoalMath.parseRubles("6500"), 650_000)
        XCTAssertEqual(CoalMath.parseRubles("6 500"), 650_000)
        XCTAssertEqual(CoalMath.parseRubles("6\u{00A0}500,5"), 650_050)
        XCTAssertEqual(CoalMath.parseRubles("1200.00"), 120_000)
        XCTAssertEqual(CoalMath.parseRubles("0"), 0)
        XCTAssertNil(CoalMath.parseRubles("12,345"))
        XCTAssertNil(CoalMath.parseRubles("-5"))
        XCTAssertNil(CoalMath.parseRubles(""))
    }

    func testTexts() {
        XCTAssertEqual(CoalMath.rubles(1_400_000), "14\u{00A0}000\u{00A0}₽")
        XCTAssertEqual(CoalMath.rubles(1_625_050), "16\u{00A0}250,50\u{00A0}₽")
        XCTAssertEqual(CoalMath.rubles(100_000_000), "1\u{00A0}000\u{00A0}000\u{00A0}₽")
        XCTAssertEqual(CoalMath.rubles(-120_000), "−1\u{00A0}200\u{00A0}₽")
        XCTAssertEqual(CoalMath.rubles(0), "0\u{00A0}₽")
        XCTAssertEqual(CoalMath.rublesField(650_000), "6500")
        XCTAssertEqual(CoalMath.rublesField(120_050), "1200,50")
        XCTAssertEqual(CoalMath.tons(2000), "2")
        XCTAssertEqual(CoalMath.tons(2500), "2,5")
        XCTAssertEqual(CoalMath.tons(125), "0,125")
        XCTAssertEqual(CoalMath.tons(1_250_000), "1\u{00A0}250")
        XCTAssertEqual(CoalMath.tonsText(500), "0,5\u{00A0}т")
        XCTAssertEqual(CoalMath.spreadsheetRubles(1_625_050), "16250.50")
        XCTAssertEqual(CoalMath.spreadsheetRubles(1_400_000), "14000")
        XCTAssertEqual(CoalMath.spreadsheetTons(2500), "2.5")
        XCTAssertEqual(CoalMath.spreadsheetTons(1_250_000), "1250")
    }

    func testSaleListTotalAndAdjustment() {
        let sale = CoalSale(saleDate: Date(), kilograms: 2000, locationNameSnapshot: "Новоцурухайтуй",
                            coalPricePerTonSnapshot: 650_000, deliveryPriceSnapshot: 100_000,
                            coalAmount: 1_300_000, deliveryPrice: 100_000, vehicleType: .own,
                            createdAt: Date(), updatedAt: Date())
        XCTAssertEqual(sale.totalAmount, 1_400_000)
        XCTAssertEqual(sale.listTotal, 1_400_000)
        XCTAssertFalse(sale.isPriceAdjusted)
        sale.coalAmount = 1_250_000
        sale.totalAmount = sale.coalAmount + sale.deliveryPrice
        XCTAssertTrue(sale.isPriceAdjusted)
        XCTAssertEqual(sale.listTotal, 1_400_000)
    }

    func testDriverNameParts() {
        let d = Driver(lastName: " мунгалов", firstName: "Сергей", middleName: "", createdAt: Date())
        XCTAssertEqual(d.letter, "М")
        XCTAssertEqual(d.fullName, "мунгалов Сергей")
        let location = DeliveryLocation(name: "Приаргунск", deliveryPriceKopecks: 120_000, createdAt: Date())
        XCTAssertEqual(location.effectiveDriverPaymentKopecks, 120_000)
        location.driverPaymentKopecks = 100_000
        XCTAssertEqual(location.effectiveDriverPaymentKopecks, 100_000)
    }
}
