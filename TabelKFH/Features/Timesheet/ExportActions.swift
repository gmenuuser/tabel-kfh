import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The PDF and the XLSX of a timesheet, written to a temporary folder for sharing.
struct ExportedFiles: Equatable {
    let pdf: URL
    let xlsx: URL

    /// PDF first, then XLSX.
    var all: [URL] { [pdf, xlsx] }
}

enum ExportFiles {
    /// `tmp/Export/`.
    static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Export", isDirectory: true)
    }

    /// Empties `directory`, then writes «Табель_<Месяц>_<год>[_<Фамилия>]_v<N>.pdf» and «….xlsx» into it,
    /// so only the shown version's files stay. `surname` is given for one employee's timesheet.
    static func write(_ d: TimesheetDocument, surname: String? = nil,
                      in directory: URL = ExportFiles.directory) throws -> ExportedFiles {
        let manager = FileManager.default
        if manager.fileExists(atPath: directory.path) {
            try manager.removeItem(at: directory)
        }
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let pdf = directory.appendingPathComponent(exportFileName(d, surname: surname, ext: "pdf"))
        let xlsx = directory.appendingPathComponent(exportFileName(d, surname: surname, ext: "xlsx"))
        try TimesheetPDFRenderer.render(d).write(to: pdf, options: .atomic)
        try XLSXWriter.make(d).write(to: xlsx, options: .atomic)
        return ExportedFiles(pdf: pdf, xlsx: xlsx)
    }
}

/// Renders and writes the export files off the main thread, one version at a time,
/// so the files of two versions never mix in `tmp/Export`.
private actor ExportWriter {
    static let shared = ExportWriter()

    func write(_ d: TimesheetDocument, surname: String?) throws -> ExportedFiles {
        try ExportFiles.write(d, surname: surname)
    }
}

extension UTType {
    /// Excel workbook (.xlsx).
    static let xlsxWorkbook = UTType("org.openxmlformats.spreadsheetml.sheet") ?? .spreadsheet
}

/// Bytes handed to `.fileExporter`; the content type decides the file extension.
struct ExportedFile: FileDocument {
    static let readableContentTypes: [UTType] = [.pdf, .xlsxWorkbook, .json]

    let data: Data
    let contentType: UTType

    init(data: Data, contentType: UTType) {
        self.data = data
        self.contentType = contentType
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
        self.contentType = configuration.contentType
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// «Скачать PDF», «Экспорт в Excel», «Распечатать», «Поделиться» for the shown timesheet version.
struct ExportActions: View {
    let document: TimesheetDocument
    /// The employee's surname for one employee's timesheet; nil for the whole farm.
    let surname: String?

    /// What the files are written from; a change writes them again.
    private struct Source: Equatable {
        let document: TimesheetDocument
        let surname: String?
    }

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
                    // Shown for the moment the files are being written.
                    Button("Поделиться") {}
                        .buttonStyle(BigButtonStyle(color: Theme.accent))
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.8)
        .disabled(files == nil)
        .task(id: Source(document: document, surname: surname)) { await prepare() }
        .fileExporter(isPresented: $isExporting, document: exporting,
                      contentType: exporting?.contentType ?? .pdf, defaultFilename: exportName) { result in
            switch result {
            case .success:
                message = "Файл сохранён"
            case .failure(let error):
                if !error.isCancellation { message = "Не удалось сохранить файл: \(error.localizedDescription)" }
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    /// Writes both files of the shown version off the main thread so the screen stays responsive;
    /// the buttons stay disabled until the files are ready.
    private func prepare() async {
        files = nil
        do {
            let written = try await ExportWriter.shared.write(document, surname: surname)
            // Another version was chosen meanwhile: its own task writes and shows its files.
            guard !Task.isCancelled else { return }
            files = written
        } catch {
            guard !Task.isCancelled else { return }
            message = "Не удалось подготовить файлы табеля: \(error.localizedDescription)"
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

    /// AirPrint of the PDF, landscape, job named after the file.
    private func printPDF() {
        guard let files, let data = try? Data(contentsOf: files.pdf) else {
            message = "Не удалось подготовить PDF для печати"
            return
        }
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = files.pdf.lastPathComponent
        info.orientation = .landscape
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printingItem = data
        controller.present(animated: true) { _, _, error in
            if let error { message = "Не удалось распечатать: \(error.localizedDescription)" }
        }
    }
}
