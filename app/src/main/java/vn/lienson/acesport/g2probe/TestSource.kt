package vn.lienson.acesport.g2probe

data class TestSource(
    val id: String,
    val name: String,
    val sourceType: String, // "content_id", "infohash", "local_file"
    val value: String,
    val qualityLabel: String,
    val expectedResolution: String,
    val status: String
)
