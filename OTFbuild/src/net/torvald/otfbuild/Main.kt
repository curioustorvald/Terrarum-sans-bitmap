package net.torvald.otfbuild

fun main(args: Array<String>) {
    val assetsDir = args.getOrElse(0) { "src/assets" }
    val outputPath = args.getOrElse(1) { "OTFbuild/TerrarumSansBitmap.kbitx" }
    KbitxBuilder(assetsDir).build(outputPath)
}
