param(
    [string] $Jar = (Join-Path $PSScriptRoot "custom_spider.jar")
)
$ErrorActionPreference = "Stop"

# When this script is launched from Gradle Exec, PowerShell 5.1 can start without
# Microsoft.PowerShell.Utility auto-loaded, which makes Get-FileHash, Write-Host, Sort-Object and
# Select-Object all unavailable. Importing it explicitly restores them.
try {
    if (-not (Get-Command Write-Host -ErrorAction SilentlyContinue)) {
        Import-Module Microsoft.PowerShell.Utility -ErrorAction Stop
    }
} catch {
    [Console]::Error.WriteLine("FAIL cannot load Microsoft.PowerShell.Utility: $_")
    exit 1
}


function Fail([string] $Message) {
    Write-Host "FAIL $Message"
    exit 1
}
# Get-FileHash lives in Microsoft.PowerShell.Utility, which is not always loaded when the script
# is launched from Gradle Exec. Fall back to .NET so the check works on any PowerShell edition.
function FileMd5([string] $Path) {
    $hasher = [Security.Cryptography.MD5]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try {
        return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $hasher.Dispose()
    }
}

function StartsWithAny([string] $Value, [string[]] $Prefixes) {
    foreach ($prefix in $Prefixes) {
        if ($Value.StartsWith($prefix, [StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

# ZipFile lives in System.IO.Compression.FileSystem, which PowerShell 5.1 does not load by
# default. PowerShell 7 has it built in; Add-Type is a no-op there.
try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop } catch { }

$apktool = Join-Path $PSScriptRoot "3rd\apktool_2.11.0.jar"
if (-not (Test-Path -LiteralPath $Jar)) { Fail "missing jar: $Jar" }
if (-not (Test-Path -LiteralPath $apktool)) { Fail "missing apktool: $apktool" }

$Jar = (Resolve-Path -LiteralPath $Jar).Path
$size = (Get-Item -LiteralPath $Jar).Length

$md5Path = "$Jar.md5"
if (-not (Test-Path -LiteralPath $md5Path)) { Fail "missing md5: $md5Path" }
$actualMd5 = FileMd5 $Jar
$expectedMd5 = (Get-Content -Raw -LiteralPath $md5Path).Trim().ToLowerInvariant()
if ($actualMd5 -ne $expectedMd5) { Fail "md5 mismatch: $actualMd5 != $expectedMd5" }

$work = Join-Path ([IO.Path]::GetTempPath()) ("catvod-checkJar-" + [Guid]::NewGuid().ToString("N"))
try {
    & java -jar $apktool d -f $Jar -o $work | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "apktool decode failed" }

    $smali = Join-Path $work "smali"
    if (-not (Test-Path -LiteralPath $smali)) { Fail "missing smali output" }

    # The offline poToken minter is carried inside the JAR, because a JAR gets no
    # nativeLibraryDir and System.loadLibrary can never resolve for it. Read the ZIP directly
    # rather than the decoded tree: apktool places lib/ under different roots depending on how
    # it classified the entries, and what matters is the shipped archive.
    # Fail exits the process, so gather the verdict first and release the archive before
    # reporting: exiting from inside the try block would leave the ZIP handle open.
    $abis = @()
    $badElf = $null
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notlike "lib/*/libpot.so") { continue }
            $abis += $entry.FullName.Split("/")[1]
            $stream = $entry.Open()
            try {
                $header = New-Object byte[] 4
                $read = $stream.Read($header, 0, 4)
                if ($read -ne 4 -or $header[0] -ne 0x7F -or $header[1] -ne 0x45 `
                        -or $header[2] -ne 0x4C -or $header[3] -ne 0x46) {
                    if (-not $badElf) { $badElf = $entry.FullName }
                }
            } finally {
                $stream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
    if (-not $abis) { Fail "missing lib/<abi>/libpot.so in jar" }
    if ($badElf) { Fail "not an ELF: $badElf" }
    Write-Host ("OK packaged libpot.so: " + (($abis | Sort-Object) -join ", "))

    foreach ($path in @("androidx", "kotlin", "javax\xml\namespace", "org\slf4j", "org\xmlpull\v1")) {
        if (Test-Path -LiteralPath (Join-Path $smali $path)) { Fail "unexpected packaged API: $path" }
    }

    $catvod = Join-Path $smali "com\github\catvod"
    if (-not (Test-Path -LiteralPath $catvod)) { Fail "missing catvod package" }
    $unexpected = Get-ChildItem -Force -LiteralPath $catvod | Where-Object {
        $_.Name -notin @("js", "spider")
    }
    if ($unexpected) { Fail ("unexpected catvod entries: " + (($unexpected.Name | Sort-Object) -join ", ")) }

    # The offline poToken minter binds a JNI symbol derived from its fully qualified class name,
    # so the bridge must ship as app.morphe.pot.helper.potokens.PoTokenServiceImpl and cannot be
    # moved under com.github.catvod. Only that one class is allowed outside the catvod tree.
    $morphe = Join-Path $smali "app\morphe"
    if (Test-Path -LiteralPath $morphe) {
        $allowedMorphe = @("PoTokenServiceImpl.smali")
        $strayMorphe = Get-ChildItem -Recurse -Force -File -LiteralPath $morphe | Where-Object {
            $_.Name -notin $allowedMorphe
        }
        if ($strayMorphe) {
            Fail ("unexpected app/morphe entries: " + (($strayMorphe.Name | Sort-Object -Unique) -join ", "))
        }
    }

    $defs = [Collections.Generic.HashSet[string]]::new()
    $refs = [Collections.Generic.HashSet[string]]::new()
    $classPattern = '(?m)^\.class[ \t]+(?:[^ \t\r\n]+[ \t]+)*L([^;\r\n]+);'
    $typePattern = '(?<![A-Za-z0-9_$])L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+(?:\$[A-Za-z0-9_$]+)?);'
    $forbiddenText = [ordered]@{
        "Lcom/google/gson/reflect/TypeToken;-><init>()V" = "illegal Gson TypeToken constructor call; use TypeToken.getParameterized"
    }

    foreach ($file in Get-ChildItem -Recurse -Filter "*.smali" -LiteralPath $smali) {
        $text = Get-Content -Raw -LiteralPath $file.FullName
        foreach ($pattern in $forbiddenText.Keys) {
            if ($text.Contains($pattern)) {
                Fail "$($forbiddenText[$pattern]): $($file.FullName)"
            }
        }
        foreach ($match in [regex]::Matches($text, $classPattern)) { [void] $defs.Add($match.Groups[1].Value) }
        foreach ($match in [regex]::Matches($text, $typePattern)) { [void] $refs.Add($match.Groups[1].Value) }
    }

    $allowed = @(
        "android/",
        "app/morphe/pot/helper/potokens/",
        "androidx/annotation/",
        "androidx/startup/",
        "androidx/tracing/",
        "com/github/catvod/crawler/",
        "com/google/gson/",
        "com/hierynomus/",
        "com/thegrizzlylabs/sardineandroid/",
        "com/whl/quickjs/",
        "dalvik/",
        "j$/",
        "java/",
        "javax/crypto/",
        "javax/net/",
        "javax/security/",
        "javax/xml/namespace/",
        "okhttp3/",
        "okio/",
        "org/json/",
        "org/slf4j/",
        "org/w3c/dom/",
        "org/xml/sax/",
        "org/xmlpull/v1/",
        "kotlin/"
    )
    # jsoup can reference re2j as an optional regex backend; the jar does not require it.
    $optional = @("com/google/re2j/")
    $missing = foreach ($ref in $refs) {
        if (-not $defs.Contains($ref) -and -not (StartsWithAny $ref $allowed) -and -not (StartsWithAny $ref $optional)) {
            $ref
        }
    }
    if ($missing) { Fail ("missing refs: " + (($missing | Sort-Object -Unique | Select-Object -First 20) -join ", ")) }

    $optionalRefs = foreach ($ref in $refs) {
        if (-not $defs.Contains($ref) -and (StartsWithAny $ref $optional)) {
            $ref
        }
    }
    if ($optionalRefs) {
        Write-Host ("WARN optional refs (jsoup optional regex backend): " + (($optionalRefs | Sort-Object -Unique) -join ", "))
    }

    Write-Host "OK $([IO.Path]::GetFileName($Jar)) $size bytes $actualMd5"
} finally {
    if (Test-Path -LiteralPath $work) {
        Remove-Item -LiteralPath $work -Recurse -Force
    }
}
