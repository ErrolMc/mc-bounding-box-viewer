param(
  [Parameter(Mandatory)][string]$ModsDir,
  [Parameter(Mandatory)][string[]]$Staged,
  [string]$McVersion = '26.3',
  [string]$LoaderVersion = '0.20.1',
  [string]$JavaVersion = '25.0.5',
  [string]$MixinExtrasVersion = '0.5.5'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------------------------------------------------------------- JSON
function ConvertFrom-Je($e) {
  switch ($e.ValueKind.ToString()) {
    'Object' { $h = [ordered]@{}; foreach ($p in $e.EnumerateObject()) { $h[$p.Name] = ConvertFrom-Je $p.Value }; return $h }
    'Array'  { $l = [System.Collections.Generic.List[object]]::new(); foreach ($x in $e.EnumerateArray()) { $l.Add((ConvertFrom-Je $x)) }; return ,$l.ToArray() }
    'String' { return $e.GetString() }
    'Number' { return $e.GetRawText() }
    'True'   { return $true }
    'False'  { return $false }
    default  { return $null }
  }
}

function Read-ModJson([System.IO.Compression.ZipArchive]$zip) {
  $entry = $zip.GetEntry('fabric.mod.json')
  if ($null -eq $entry) { return $null }
  $sr = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8, $true)
  try { $text = $sr.ReadToEnd() } finally { $sr.Dispose() }
  $opts = [System.Text.Json.JsonDocumentOptions]::new()
  $opts.CommentHandling = [System.Text.Json.JsonCommentHandling]::Skip
  $opts.AllowTrailingCommas = $true
  $doc = [System.Text.Json.JsonDocument]::Parse($text, $opts)
  try { $res = ConvertFrom-Je $doc.RootElement } finally { $doc.Dispose() }
  return $res
}

function To-List($v) {
  if ($null -eq $v) { return ,@() }
  if ($v -is [string]) { return ,@($v) }
  return ,@($v | ForEach-Object { $_ })
}

# ---------------------------------------------------------------- collect every mod (top-level and Jar-in-Jar)
$records = [System.Collections.Generic.List[object]]::new()

function Add-FromZip([System.IO.Compression.ZipArchive]$zip, [string]$label, [string]$origin, [int]$depth) {
  $m = Read-ModJson $zip
  if ($null -eq $m) { return }
  $prov = [System.Collections.Generic.List[string]]::new()
  if ($m.Contains('provides')) {
    foreach ($p in (To-List $m['provides'])) {
      if ($p -is [string]) { $prov.Add($p) }
      elseif ($p -is [System.Collections.IDictionary] -and $p.Contains('id')) { $prov.Add([string]$p['id']) }
    }
  }
  $records.Add([pscustomobject]@{
    Label = $label; Origin = $origin; Depth = $depth
    Id = [string]$m['id']; Version = [string]$m['version']; Name = [string]$m['name']
    Environment = [string]$m['environment']
    Provides = $prov.ToArray()
    Depends = $m['depends']; Breaks = $m['breaks']; Conflicts = $m['conflicts']
    Recommends = $m['recommends']; Suggests = $m['suggests']
  })
  if ($depth -lt 4 -and $m.Contains('jars')) {
    foreach ($j in (To-List $m['jars'])) {
      $file = if ($j -is [System.Collections.IDictionary]) { [string]$j['file'] } else { [string]$j }
      $ne = $zip.GetEntry($file)
      if ($null -eq $ne) { Write-Warning "nested jar listed but missing: $label!$file"; continue }
      $ms = [System.IO.MemoryStream]::new()
      $s = $ne.Open(); try { $s.CopyTo($ms) } finally { $s.Dispose() }
      $ms.Position = 0
      $nz = [System.IO.Compression.ZipArchive]::new($ms, [System.IO.Compression.ZipArchiveMode]::Read)
      try { Add-FromZip $nz "$label!$file" $origin ($depth + 1) } finally { $nz.Dispose(); $ms.Dispose() }
    }
  }
}

$existing = @(Get-ChildItem -LiteralPath $ModsDir -Filter '*.jar' -File | Sort-Object Name)
foreach ($f in $existing) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($f.FullName)
  try { Add-FromZip $z $f.Name 'existing' 0 } finally { $z.Dispose() }
}
$stagedFull = @($Staged | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
foreach ($p in $stagedFull) {
  $leaf = Split-Path $p -Leaf
  if (Test-Path -LiteralPath (Join-Path $ModsDir $leaf)) { Write-Warning "a jar named $leaf is already in the mods folder" }
  $z = [System.IO.Compression.ZipFile]::OpenRead($p)
  try { Add-FromZip $z $leaf 'STAGED' 0 } finally { $z.Dispose() }
}

# ---------------------------------------------------------------- what is present
$present = @{}
function Add-Present([string]$id, [string]$ver, [string]$src) {
  if (-not $present.ContainsKey($id)) { $present[$id] = [System.Collections.Generic.List[object]]::new() }
  $present[$id].Add([pscustomobject]@{ Version = $ver; Src = $src })
}
Add-Present 'minecraft'    $McVersion          '(built-in)'
Add-Present 'java'         $JavaVersion        '(built-in)'
Add-Present 'fabricloader' $LoaderVersion      '(built-in)'
Add-Present 'mixinextras'  $MixinExtrasVersion '(built-in: bundled with Fabric Loader)'
foreach ($r in $records) {
  Add-Present $r.Id $r.Version $r.Label
  foreach ($p in $r.Provides) { Add-Present $p $r.Version "$($r.Label) (provides)" }
}

# ---------------------------------------------------------------- Fabric-style version ranges
$script:unparseable = [System.Collections.Generic.List[string]]::new()

function Parse-Version([string]$s) {
  if ($null -eq $s) { return $null }
  $t = $s.Trim()
  $plus = $t.IndexOf('+'); if ($plus -ge 0) { $t = $t.Substring(0, $plus) }
  $pre = @()
  $dash = $t.IndexOf('-'); if ($dash -ge 0) { $pre = @($t.Substring($dash + 1).Split('.')); $t = $t.Substring(0, $dash) }
  $nums = [System.Collections.Generic.List[int64]]::new()
  foreach ($c in $t.Split('.')) {
    $n = 0L
    if (-not [int64]::TryParse($c, [ref]$n)) { return $null }
    $nums.Add($n)
  }
  return [pscustomobject]@{ Nums = $nums.ToArray(); Pre = $pre; Raw = $s }
}

function Compare-Ver($a, $b) {
  $n = [Math]::Max($a.Nums.Count, $b.Nums.Count)
  for ($i = 0; $i -lt $n; $i++) {
    $x = if ($i -lt $a.Nums.Count) { $a.Nums[$i] } else { 0L }
    $y = if ($i -lt $b.Nums.Count) { $b.Nums[$i] } else { 0L }
    if ($x -lt $y) { return -1 }
    if ($x -gt $y) { return 1 }
  }
  $ap = $a.Pre.Count -gt 0; $bp = $b.Pre.Count -gt 0
  if (-not $ap -and -not $bp) { return 0 }
  if (-not $ap) { return 1 }
  if (-not $bp) { return -1 }
  $m = [Math]::Min($a.Pre.Count, $b.Pre.Count)
  for ($i = 0; $i -lt $m; $i++) {
    $x = [string]$a.Pre[$i]; $y = [string]$b.Pre[$i]
    $xn = $x -match '^\d+$'; $yn = $y -match '^\d+$'
    if ($xn -and $yn) {
      $xi = [int64]$x; $yi = [int64]$y
      if ($xi -lt $yi) { return -1 }
      if ($xi -gt $yi) { return 1 }
    }
    elseif ($xn) { return -1 }
    elseif ($yn) { return 1 }
    else {
      $c = [string]::CompareOrdinal($x, $y)
      if ($c -lt 0) { return -1 }
      if ($c -gt 0) { return 1 }
    }
  }
  if ($a.Pre.Count -lt $b.Pre.Count) { return -1 }
  if ($a.Pre.Count -gt $b.Pre.Count) { return 1 }
  return 0
}

function New-Ver([int64[]]$nums) { return [pscustomobject]@{ Nums = $nums; Pre = @(); Raw = ($nums -join '.') } }

function Test-Token([string]$verStr, [string]$tok) {
  if ($tok -eq '*' -or $tok -eq '') { return $true }
  $v = Parse-Version $verStr
  $op = ''; $rest = $tok
  foreach ($o in '>=', '<=', '>', '<', '=', '^', '~') {
    if ($tok.StartsWith($o)) { $op = $o; $rest = $tok.Substring($o.Length); break }
  }
  if ($null -eq $v) {
    # installed version isn't numeric semver: only an exact string match can be confirmed
    if ($op -eq '' -or $op -eq '=') { return ($verStr -eq $rest) }
    $script:unparseable.Add("version '$verStr' vs range token '$tok'")
    return $false
  }
  if ($rest -match '(^|\.)[xX*]($|\.)') {
    $fixed = [System.Collections.Generic.List[int64]]::new()
    foreach ($c in $rest.Split('.')) {
      if ($c -match '^[xX*]$') { break }
      $n = 0L
      if (-not [int64]::TryParse($c, [ref]$n)) { $script:unparseable.Add("range token '$tok'"); return $false }
      $fixed.Add($n)
    }
    if ($fixed.Count -eq 0) { return $true }
    $lo = New-Ver $fixed.ToArray()
    if ($op -eq '>=' -or $op -eq '>') { return ((Compare-Ver $v $lo) -ge 0) }
    $hiNums = $fixed.ToArray(); $hiNums[$hiNums.Length - 1]++
    $hi = New-Ver $hiNums
    return (((Compare-Ver $v $lo) -ge 0) -and ((Compare-Ver $v $hi) -lt 0))
  }
  $r = Parse-Version $rest
  if ($null -eq $r) { $script:unparseable.Add("range token '$tok'"); return $false }
  switch ($op) {
    ''  { return ((Compare-Ver $v $r) -eq 0) }
    '=' { return ((Compare-Ver $v $r) -eq 0) }
    '>=' { return ((Compare-Ver $v $r) -ge 0) }
    '>'  { return ((Compare-Ver $v $r) -gt 0) }
    '<=' { return ((Compare-Ver $v $r) -le 0) }
    '<'  { return ((Compare-Ver $v $r) -lt 0) }
    '~' {
      $hiNums = if ($r.Nums.Count -ge 2) { @($r.Nums[0], ($r.Nums[1] + 1)) } else { @(($r.Nums[0] + 1)) }
      return (((Compare-Ver $v $r) -ge 0) -and ((Compare-Ver $v (New-Ver ([int64[]]$hiNums))) -lt 0))
    }
    '^' {
      $n0 = $r.Nums[0]; $n1 = if ($r.Nums.Count -ge 2) { $r.Nums[1] } else { 0L }; $n2 = if ($r.Nums.Count -ge 3) { $r.Nums[2] } else { 0L }
      $hiNums = if ($n0 -gt 0) { @(($n0 + 1)) } elseif ($n1 -gt 0) { @(0L, ($n1 + 1)) } else { @(0L, 0L, ($n2 + 1)) }
      return (((Compare-Ver $v $r) -ge 0) -and ((Compare-Ver $v (New-Ver ([int64[]]$hiNums))) -lt 0))
    }
  }
  return $false
}

# one range = space-separated tokens, all must hold
function Test-Range([string]$verStr, [string]$range) {
  foreach ($tok in ($range.Trim() -split '\s+')) { if (-not (Test-Token $verStr $tok)) { return $false } }
  return $true
}

# ---------------------------------------------------------------- evaluate depends / breaks / conflicts
$problems = [System.Collections.Generic.List[string]]::new()
$warns    = [System.Collections.Generic.List[string]]::new()
$detail   = [System.Collections.Generic.List[string]]::new()

function Get-Matches($id, $ranges) {
  $hits = @()
  if (-not $present.ContainsKey($id)) { return ,@() }
  foreach ($cand in $present[$id]) {
    foreach ($rg in $ranges) {
      if (Test-Range $cand.Version ([string]$rg)) { $hits += $cand; break }
    }
  }
  return ,$hits
}

foreach ($r in $records) {
  $who = "$($r.Label) [$($r.Id) $($r.Version)]"
  if ($r.Depends -is [System.Collections.IDictionary]) {
    foreach ($k in @($r.Depends.Keys)) {
      $ranges = To-List $r.Depends[$k]
      $hits = Get-Matches $k $ranges
      $rs = ($ranges -join ' | ')
      if (-not $present.ContainsKey($k)) {
        $problems.Add("MISSING   $who needs '$k' ($rs) - not in pack")
        if ($r.Origin -eq 'STAGED' -and $r.Depth -eq 0) { $detail.Add("    [$($r.Id)]depends $k ($rs) -> MISSING") }
      }
      elseif ($hits.Count -eq 0) {
        $have = ($present[$k] | ForEach-Object { "$($_.Version) from $($_.Src)" }) -join '; '
        $problems.Add("VERSION   $who needs '$k' ($rs) but pack has: $have")
        if ($r.Origin -eq 'STAGED' -and $r.Depth -eq 0) { $detail.Add("    [$($r.Id)]depends $k ($rs) -> NO MATCH (have $have)") }
      }
      elseif ($r.Origin -eq 'STAGED' -and $r.Depth -eq 0) {
        $detail.Add("    [$($r.Id)]depends $k ($rs) -> ok: $(($hits | ForEach-Object { "$($_.Version) from $($_.Src)" }) -join '; ')")
      }
    }
  }
  if ($r.Breaks -is [System.Collections.IDictionary]) {
    foreach ($k in @($r.Breaks.Keys)) {
      $ranges = To-List $r.Breaks[$k]
      $hits = Get-Matches $k $ranges
      $rs = ($ranges -join ' | ')
      if ($hits.Count -gt 0) {
        $problems.Add("BREAKS    $who declares it breaks '$k' ($rs); pack has: $(($hits | ForEach-Object { "$($_.Version) from $($_.Src)" }) -join '; ')")
        if ($r.Origin -eq 'STAGED' -and $r.Depth -eq 0) { $detail.Add("    [$($r.Id)]breaks  $k ($rs) -> CONFLICT") }
      }
      elseif ($r.Origin -eq 'STAGED' -and $r.Depth -eq 0) { $detail.Add("    [$($r.Id)]breaks  $k ($rs) -> not present, ok") }
    }
  }
  if ($r.Conflicts -is [System.Collections.IDictionary]) {
    foreach ($k in @($r.Conflicts.Keys)) {
      $ranges = To-List $r.Conflicts[$k]
      $hits = Get-Matches $k $ranges
      if ($hits.Count -gt 0) { $warns.Add("CONFLICTS $who conflicts with '$k' ($($ranges -join ' | ')); pack has: $(($hits | ForEach-Object { "$($_.Version) from $($_.Src)" }) -join '; ')") }
    }
  }
  foreach ($soft in 'Recommends', 'Suggests') {
    if ($r.$soft -is [System.Collections.IDictionary] -and $r.Origin -eq 'STAGED' -and $r.Depth -eq 0) {
      foreach ($k in @($r.$soft.Keys)) {
        $ranges = To-List $r.$soft[$k]
        $hits = Get-Matches $k $ranges
        $state = if ($hits.Count -gt 0) { 'present' } else { 'not installed (optional)' }
        $detail.Add("    [$($r.Id)]$($soft.ToLower()) $k ($($ranges -join ' | ')) -> $state")
      }
    }
  }
}

# duplicates of a mod id between different jars
$dupGroups = $records | Group-Object Id | Where-Object {
  (@($_.Group | Select-Object -ExpandProperty Label -Unique).Count -gt 1) -and (@($_.Group | Where-Object Origin -eq 'STAGED').Count -gt 0)
}
foreach ($g in $dupGroups) {
  $warns.Add("DUPLICATE id '$($g.Name)': " + ((@($g.Group) | ForEach-Object { "$($_.Version) in $($_.Label)" }) -join '; '))
}

# ---------------------------------------------------------------- report
$top = @($records | Where-Object { $_.Depth -eq 0 })
$nested = @($records | Where-Object { $_.Depth -gt 0 })
"== {0} top-level mods ({1} existing + {2} staged), {3} nested Jar-in-Jar modules ==" -f $top.Count, @($top | Where-Object Origin -eq 'existing').Count, @($top | Where-Object Origin -eq 'STAGED').Count, $nested.Count
foreach ($r in $top) { "{0,-9} {1,-34} {2,-22} {3}" -f $r.Origin, $r.Id, $r.Version, $r.Label }
''
foreach ($r in ($records | Where-Object { $_.Origin -eq 'STAGED' })) {
  $nestedNote = if ($r.Depth -gt 0) { "  (nested in $($r.Label.Split('!')[0]))" } else { '' }
  if ($r.Depth -eq 0) {
    "-- STAGED: $($r.Name) / id=$($r.Id) / version=$($r.Version) / environment=$($r.Environment) / provides=[$($r.Provides -join ', ')]"
    $nestedIds = @($records | Where-Object { $_.Depth -gt 0 -and $_.Label.StartsWith($r.Label + '!') } | ForEach-Object { "$($_.Id)@$($_.Version)" })
    if ($nestedIds.Count -gt 0) { "    nested modules: $($nestedIds -join ', ')" }
    $detail | Out-Null
  }
}
''
"-- Dependency detail for STAGED top-level mods (printed in jar order; all staged jars combined) --"
$detail
''
"-- Unparseable version comparisons: $($script:unparseable.Count)"
$script:unparseable | Select-Object -Unique
''
"-- WARNINGS: $($warns.Count)"
$warns
''
"-- PROBLEMS: $($problems.Count)"
$problems
