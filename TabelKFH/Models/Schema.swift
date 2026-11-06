import Foundation
import SwiftData

enum SchemaV1: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(1, 0, 0) }

    static var models: [any PersistentModel.Type] {
        [Employee.self, AttendanceRecord.self, FarmSettings.self, TimesheetSnapshot.self]
    }
}

enum TabelMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [SchemaV1.self] }
    static var stages: [MigrationStage] { [] }
}

/// The app's store. `inMemory` wins over `url`; with neither, the default on-disk store is used.
func makeModelContainer(inMemory: Bool = false, url: URL? = nil) throws -> ModelContainer {
    let schema = Schema(versionedSchema: SchemaV1.self)
    let configuration: ModelConfiguration
    if inMemory {
        configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
    } else if let url {
        configuration = ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none)
    } else {
        configuration = ModelConfiguration(schema: schema, cloudKitDatabase: .none)
    }
    return try ModelContainer(for: schema, migrationPlan: TabelMigrationPlan.self,
                              configurations: [configuration])
}
