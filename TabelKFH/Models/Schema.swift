import Foundation
import SwiftData

// How the stored models change without losing the owner's data (the app must always open every shipped store):
//
// - A version lists the model classes of its store. A class that is not edited may be listed by several
//   versions (V1's four classes are also V2's): its shape is the same in both.
// - Adding new model classes is a lightweight stage (V1 → V2 added the coal module).
// - Editing a shipped class: first freeze the old shape as a nested copy inside the old version
//   (`extension SchemaV2 { @Model final class Employee { … } }`, same stored properties, same names, listed in
//   that version's `models`), then change the top-level class and add the new version with
//   `.lightweight(…)` for added optional/defaulted properties or renames marked `@Attribute(originalName:)`,
//   or `.custom(…)` when values must be computed.
// - Always add a test that creates a store with the previous version at a temporary URL, opens it with
//   `makeModelContainer(url:)` and checks every value survived (`CoalPersistenceTests`).
// - Never edit a version after it ships and never remove one from `schemas`: a phone that skipped versions
//   migrates through every stage in order. Raise `BackupCodec.schemaVersion` only when the backup format changes.
enum SchemaV1: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(1, 0, 0) }

    static var models: [any PersistentModel.Type] {
        [Employee.self, AttendanceRecord.self, FarmSettings.self, TimesheetSnapshot.self]
    }
}

/// V1 + the coal module («Продажа угля»).
enum SchemaV2: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(2, 0, 0) }

    static var models: [any PersistentModel.Type] {
        SchemaV1.models + [CoalSettings.self, DeliveryLocation.self, Driver.self, CoalSale.self,
                           DriverPayment.self, CoalWorkDay.self]
    }
}

/// V2 + the livestock module («Скот и забой»).
enum SchemaV3: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(3, 0, 0) }

    static var models: [any PersistentModel.Type] {
        SchemaV2.models + [LivestockCategory.self, LivestockOperation.self, SlaughterRecord.self,
                           LivestockSettings.self]
    }
}

/// V3 + saved calculations of «Агрономические калькуляторы».
enum SchemaV4: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(4, 0, 0) }

    static var models: [any PersistentModel.Type] { SchemaV3.models + [AgroCalculation.self] }
}

/// V4 + the timesheet of hours (0, 4, 8, 12). The status records of V1 stay as they are.
enum SchemaV5: VersionedSchema {
    static var versionIdentifier: Schema.Version { Schema.Version(5, 0, 0) }

    static var models: [any PersistentModel.Type] { SchemaV4.models + [WorkHoursMark.self] }
}

enum TabelMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] {
        [SchemaV1.self, SchemaV2.self, SchemaV3.self, SchemaV4.self, SchemaV5.self]
    }
    static var stages: [MigrationStage] {
        [.lightweight(fromVersion: SchemaV1.self, toVersion: SchemaV2.self),
         .lightweight(fromVersion: SchemaV2.self, toVersion: SchemaV3.self),
         .lightweight(fromVersion: SchemaV3.self, toVersion: SchemaV4.self),
         .lightweight(fromVersion: SchemaV4.self, toVersion: SchemaV5.self)]
    }
}

/// The app's store. `inMemory` wins over `url`; with neither, the default on-disk store is used.
func makeModelContainer(inMemory: Bool = false, url: URL? = nil) throws -> ModelContainer {
    let schema = Schema(versionedSchema: SchemaV5.self)
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
