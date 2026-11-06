import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The PDF and the XLSX of a coal report, written to `tmp/CoalExport/` for sharing.
enum CoalExportFiles {
    static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("CoalExport", isDirectory: true)
    }

    /// Empties `directory`, then writes «<fileName>.pdf» and «<fileName>.xlsx».
    static func write(_ d: CoalReportDocument, in directory: URL = CoalExportFiles.directory) throws -> ExportedFiles {
        let manager = FileManager.default
        if manager.fileExists(atPath: directory.path) { try manager.removeItem(at: directory) }
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let pdf = directory.appendingPathComponent(d.fileName + ".pdf")
        let xlsx = directory.appendingPathComponent(d.fileName + ".xlsx")
        try CoalReportPDFRenderer.render(d).write(to: pdf, options: .atomic)
        try CoalReportXLSXWriter.make(d).write(to: xlsx, options: .atomic)
        return ExportedFiles(pdf: pdf, xlsx: xlsx)
    }
}

/// Writes one report's files at a time, off the main thread.
private actor CoalExportWriter {
    static let shared = CoalExportWriter()

    func write(_ d: CoalReportDocument) throws -> ExportedFiles {
        try CoalExportFiles.write(d)
    }
}

/// «Скачать PDF», «Экспорт в Excel», «Распечатать» (AirPrint), «Поделиться» for the shown report.
struct CoalExportActions: View {
    let document: CoalReportDocument

    @State private var files: ExportedFiles?
    @State private var exporting: ExportedFile?
    @State private var exportName = ""
    @State private var isExporting = false
    @State private var message: String?

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button("Скачать PDF") { export(\.pdf, as: .pdf) }
                    .buttonStyle(BigButtonStyle())
                Button("Экспорт в Excel") { export(\.xlsx, as: .xlsxWorkbook) }
                    .buttonStyle(BigButtonStyle())
            }
            HStack(spacing: 10) {
                Button("Распечатать", action: printPDF)
                    .buttonStyle(BigButtonStyle(color: Theme.accent))
                if let files {
                    ShareLink(items: files.all) { Text("Поделиться") }
                        .buttonStyle(BigButtonStyle(color: Theme.accent))
                } else {
                    Button("Поделиться") {}
                        .buttonStyle(BigButtonStyle(color: Theme.accent))
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .disabled(files == nil)
        .task(id: document) { await prepare() }
        .fileExporter(isPresented: $isExporting, document: exporting,
                      contentType: exporting?.contentType ?? .pdf, defaultFilename: exportName) { result in
            switch result {
            case .success: message = "Файл сохранён"
            case .failure(let error):
                if !error.isCancellation { message = "Не удалось сохранить файл: \(error.localizedDescription)" }
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func prepare() async {
        files = nil
        do {
            let written = try await CoalExportWriter.shared.write(document)
            guard !Task.isCancelled else { return }
            files = written
        } catch {
            guard !Task.isCancelled else { return }
            message = "Не удалось подготовить файлы отчёта: \(error.localizedDescription)"
        }
    }

    private func export(_ file: KeyPath<ExportedFiles, URL>, as type: UTType) {
        guard let files else { return }
        do {
            let url = files[keyPath: file]
            exporting = ExportedFile(data: try Data(contentsOf: url), contentType: type)
            exportName = url.deletingPathExtension().lastPathComponent
            isExporting = true
        } catch {
            message = "Не удалось прочитать файл: \(error.localizedDescription)"
        }
    }

    /// AirPrint of the PDF, A4 portrait.
    private func printPDF() {
        guard let files, let data = try? Data(contentsOf: files.pdf) else {
            message = "Не удалось подготовить PDF для печати"
            return
        }
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = files.pdf.lastPathComponent
        info.orientation = .portrait
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printingItem = data
        controller.present(animated: true) { _, _, error in
            if let error { message = "Не удалось распечатать: \(error.localizedDescription)" }
        }
    }
}
