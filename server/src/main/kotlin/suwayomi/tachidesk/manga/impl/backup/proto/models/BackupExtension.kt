package suwayomi.tachidesk.manga.impl.backup.proto.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// suwayomi: an extension that was installed when the backup was made
@Serializable
data class BackupExtension(
    @ProtoNumber(1) var pkgName: String = "",
    @ProtoNumber(2) var name: String = "",
    // the extension store it came from, so a restore can add that store back first
    @ProtoNumber(3) var storeIndexUrl: String = "",
)
