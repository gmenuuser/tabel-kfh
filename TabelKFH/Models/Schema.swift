import Foundation
import SwiftData

// How to change the stored models without losing the owner's data (the app must always open a V1 store):
//
// 1. Freeze V1 first. Move today's model classes into `SchemaV1` as nested copies
//    (`extension SchemaV1 { @Model final class Employee { … } }`, same stored properties, same names) and list
//    those nested types in `SchemaV1.models`. From then on V1 never changes: it describes the stores that exist
//    on phones.
// 2. Add `enum SchemaV2: VersionedSchema` (`Schema.Version(2, 0, 0)`) with its own nested copies carrying the
//    change, and point the app's typealiases at them (`typealias Employee = SchemaV2.Employee`).
// 3. In `TabelMigrationPlan` append `SchemaV2.self` to `schemas` and a stage to `stages`:
//    `.lightweight(fromVersion: SchemaV1.self, toVersion: SchemaV2.self)` for added optional/defaulted
//    properties or renames marked `@Attribute(originalName:)`; `.custom(fromVersion:toVersion:willMigrate:didMigrate:)`
//    when values must be computed (fill the new fields in `didMigrate`, then save).
// 4. Make `makeModelContainer` build `Schema(versionedSchema: SchemaV2.self)`.
// 5. Test before shipping: create a store with the V1 container at a temporary URL, insert data, close it,
//    open it with the new container (`makeModelContainer(url:)`) and check every value survived; also raise
//    `BackupCodec.schemaVersion` only when the backup format changes.
// Never edit V1's nested models after they ship, and never remove a schema from `schemas`: a phone that skipped
// versions migrates through every stage in order.
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
