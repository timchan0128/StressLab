# StressLab 图标生成脚本
# 输入：TherTest.png（深蓝底 + 芯片温度计线性图案）
# 输出：
#   drawable/ic_launcher_background.xml      自适应图标不透明渐变背景（解决圆形遮罩问题）
#   mipmap-*/ic_launcher_fg.png              透明前景，4x 超采样（mdpi 432 … xxxhdpi 1728，解决模糊）
#   mipmap-*/ic_launcher.png(+round)         全底 legacy 图标（mdpi 192 … xxxhdpi 768）
[void][System.Reflection.Assembly]::LoadWithPartialName("System.Drawing")

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcPath = Join-Path $root 'TherTest.png'
$res = Join-Path $root 'app\src\main\res'

$src = [System.Drawing.Bitmap]::FromFile($srcPath)
$W = [int]$src.Width; $H = [int]$src.Height
Write-Host "source ${W}x${H}"

# ---------- LockBits 读取像素 ----------
$rect = New-Object System.Drawing.Rectangle 0,0,$W,$H
$data = $src.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $H)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$src.UnlockBits($data)

function Get-Pixel($x,$y) {
    $i = $y * $stride + $x * 4
    return [pscustomobject]@{ B=$bytes[$i]; G=$bytes[$i+1]; R=$bytes[$i+2]; A=$bytes[$i+3] }
}
function Lum($p) { return (0.299*$p.R + 0.587*$p.G + 0.114*$p.B) }

# ---------- 采样四角背景色 ----------
function Avg-Color($x0,$y0,$x1,$y1) {
    $r=0;$g=0;$b=0;$n=0
    for ($y=$y0; $y -lt $y1; $y+=4) { for ($x=$x0; $x -lt $x1; $x+=4) {
        $p = Get-Pixel $x $y; $r+=$p.R; $g+=$p.G; $b+=$p.B; $n++
    }}
    return ('#{0:X2}{1:X2}{2:X2}' -f [int]($r/$n),[int]($g/$n),[int]($b/$n))
}
$m = 25
$cTL = Avg-Color $m $m ($m+40) ($m+40)
$cBR = Avg-Color ($W-$m-40) ($H-$m-40) ($W-$m) ($H-$m)
$cC  = Avg-Color ([int]($W/2-20)) 20 ([int]($W/2+20)) 60
Write-Host "bg colors: TL=$cTL centerTop=$cC BR=$cBR"

# ---------- 亮度键控：图案包围盒 ----------
$thr = 55
$minX=$W; $minY=$H; $maxX=0; $maxY=0
for ($y=0; $y -lt $H; $y++) {
    $rowOff = $y*$stride
    for ($x=0; $x -lt $W; $x++) {
        $i = $rowOff + $x*4
        $L = 0.299*$bytes[$i+2] + 0.587*$bytes[$i+1] + 0.114*$bytes[$i]
        if ($L -gt $thr) {
            if ($x -lt $minX) {$minX=$x}; if ($x -gt $maxX) {$maxX=$x}
            if ($y -lt $minY) {$minY=$y}; if ($y -gt $maxY) {$maxY=$y}
        }
    }
}
$pad = 8
$minX=[Math]::Max(0,$minX-$pad); $minY=[Math]::Max(0,$minY-$pad)
$maxX=[Math]::Min($W-1,$maxX+$pad); $maxY=[Math]::Min($H-1,$maxY+$pad)
$gw=$maxX-$minX+1; $gh=$maxY-$minY+1
Write-Host "glyph bbox: $minX,$minY ${gw}x${gh}"

# ---------- 生成 alpha 键控图案位图（透明底，保留原色+发光） ----------
$glyph = New-Object System.Drawing.Bitmap($gw,$gh,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$grect = New-Object System.Drawing.Rectangle 0,0,$gw,$gh
$gdata = $glyph.LockBits($grect,[System.Drawing.Imaging.ImageLockMode]::WriteOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gstride = $gdata.Stride
$gbytes = New-Object byte[] ($gstride*$gh)
for ($y=0; $y -lt $gh; $y++) {
    for ($x=0; $x -lt $gw; $x++) {
        $si = ($y+$minY)*$stride + ($x+$minX)*4
        $gi = $y*$gstride + $x*4
        $L = 0.299*$bytes[$si+2] + 0.587*$bytes[$si+1] + 0.114*$bytes[$si]
# 背景最亮处 L≈48；55 以下全透明，90 以上全不透明（只保留真正的亮线条，去掉深蓝脏光晕）
        $a = [int][Math]::Min(255,[Math]::Max(0,($L-55)*7.3))
        $gbytes[$gi]   = $bytes[$si]
        $gbytes[$gi+1] = $bytes[$si+1]
        $gbytes[$gi+2] = $bytes[$si+2]
        $gbytes[$gi+3] = $a
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($gbytes,0,$gdata.Scan0,$gbytes.Length)
$glyph.UnlockBits($gdata)

# ---------- 高质量绘图工具 ----------
function New-Canvas($size) { New-Object System.Drawing.Bitmap $size,$size,([System.Drawing.Imaging.PixelFormat]::Format32bppArgb) }
function HQ-Graphics($bmp) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return $g
}
function Save-Png($bmp,$path) {
    $dir = Split-Path -Parent $path
    if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($path,[System.Drawing.Imaging.ImageFormat]::Png)
}

# ---------- 1) 自适应前景：完整不透明方图（深蓝底+图案），图案居中安全区 ----------
$fgSizes = @{ 'mipmap-mdpi'=432; 'mipmap-hdpi'=648; 'mipmap-xhdpi'=864;
              'mipmap-xxhdpi'=1296; 'mipmap-xxxhdpi'=1728 }
foreach ($d in $fgSizes.Keys) {
    $S = $fgSizes[$d]
    $bmp = New-Canvas $S
    $g = HQ-Graphics $bmp
    # 关键：前景层铺满不透明深蓝渐变底（对齐 UltraRadio——完整图标烘焙进 fg，
    # 这样只渲染 foreground 的图标提取器/PC 工具也能得到正方形图标，而不是透明底）
    $cA = [System.Drawing.Color]::FromArgb(255,0x15,0x2C,0x59)
    $cB = [System.Drawing.Color]::FromArgb(255,0x04,0x0D,0x2E)
    $brg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Rectangle 0,0,$S,$S),$cA,$cB,135)
    $g.FillRectangle($brg,0,0,$S,$S)
    $brg.Dispose()
    # 图案居中在 72dp 安全区（占 64%）
    $side = [int]($S*0.64)
    $dw = $side; $dh = $side
    if ($gw -ge $gh) { $dh=[int]($side*$gh/$gw) } else { $dw=[int]($side*$gw/$gh) }
    $dx=[int](($S-$dw)/2); $dy=[int](($S-$dh)/2)
    $g.DrawImage($glyph,$dx,$dy,$dw,$dh)
    $g.Dispose()
    Save-Png $bmp (Join-Path $res "$d\ic_launcher_fg.png")
    $bmp.Dispose()
    Write-Host "fg $d ${S}px (opaque full-bleed)"
}

# ---------- 2) 全底直角图标（唯一通道，对齐 UltraRadio）：满幅源图裁剪，全密度超采样 ----------
# 不再使用 anydpi-v26 adaptive XML：宿主 loadIcon() 对 adaptive 输出四角透明的圆角形状图，
# PC 调试软件据此渲染成圆形；纯 PNG（BitmapDrawable）才会得到满幅不透明方形缓存。
$legSizes = @{ 'mipmap-mdpi'=432; 'mipmap-hdpi'=648; 'mipmap-xhdpi'=864;
               'mipmap-xxhdpi'=1296; 'mipmap-xxxhdpi'=1728 }
$ccx = [int](($minX+$maxX)/2); $ccy=[int](($minY+$maxY)/2)
$cropSide = [int]([Math]::Max($gw,$gh)*1.30)
if ($cropSide -gt [Math]::Min($W,$H)) { $cropSide=[Math]::Min($W,$H) }
$cx0=[Math]::Max(0,[Math]::Min($W-$cropSide,$ccx-[int]($cropSide/2)))
$cy0=[Math]::Max(0,[Math]::Min($H-$cropSide,$ccy-[int]($cropSide/2)))
$crop = New-Object System.Drawing.Rectangle $cx0,$cy0,$cropSide,$cropSide
foreach ($d in $legSizes.Keys) {
    $S = $legSizes[$d]
    $bmp = New-Canvas $S
    $g = HQ-Graphics $bmp
    $g.DrawImage($src,(New-Object System.Drawing.Rectangle 0,0,$S,$S),$crop,[System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    Save-Png $bmp (Join-Path $res "$d\ic_launcher.png")
    Save-Png $bmp (Join-Path $res "$d\ic_launcher_round.png")
    $bmp.Dispose()
    Write-Host "legacy $d ${S}px"
}

$glyph.Dispose(); $src.Dispose()
Write-Host "DONE"
