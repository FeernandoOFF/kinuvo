package kinuvo.ui.design

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*

fun catalogIcon(name: String?): ImageVector = when (name) {
    "accountCircle" -> Lucide.CircleUser
    "add" -> Lucide.Plus
    "arrowBack" -> Lucide.ArrowLeft
    "arrowForward" -> Lucide.ArrowRight
    "attachFile" -> Lucide.Paperclip
    "calendarToday" -> Lucide.Calendar
    "call" -> Lucide.Phone
    "camera" -> Lucide.Camera
    "check" -> Lucide.Check
    "close" -> Lucide.X
    "delete" -> Lucide.Trash
    "download" -> Lucide.Download
    "edit" -> Lucide.Pencil
    "event" -> Lucide.Calendar
    "error" -> Lucide.CircleAlert
    "fastForward" -> Lucide.FastForward
    "favorite" -> Lucide.Heart
    "favoriteOff" -> Lucide.HeartOff
    "folder" -> Lucide.Folder
    "help" -> Lucide.CircleQuestionMark
    "home" -> Lucide.House
    "info" -> Lucide.Info
    "locationOn" -> Lucide.MapPin
    "lock" -> Lucide.Lock
    "lockOpen" -> Lucide.LockOpen
    "mail" -> Lucide.Mail
    "menu" -> Lucide.Menu
    "moreVert" -> Lucide.EllipsisVertical
    "moreHoriz" -> Lucide.Ellipsis
    "notificationsOff" -> Lucide.BellOff
    "notifications" -> Lucide.Bell
    "pause" -> Lucide.Pause
    "payment" -> Lucide.CreditCard
    "person" -> Lucide.User
    "phone" -> Lucide.Phone
    "photo" -> Lucide.Image
    "play" -> Lucide.Play
    "print" -> Lucide.Printer
    "refresh" -> Lucide.RefreshCw
    "rewind" -> Lucide.Rewind
    "search" -> Lucide.Search
    "send" -> Lucide.Send
    "settings" -> Lucide.Settings
    "share" -> Lucide.Share2
    "shoppingCart" -> Lucide.ShoppingCart
    "skipNext" -> Lucide.SkipForward
    "skipPrevious" -> Lucide.SkipBack
    "star" -> Lucide.Star
    "starHalf" -> Lucide.StarHalf
    "starOff" -> Lucide.StarOff
    "stop" -> Lucide.Square
    "upload" -> Lucide.Upload
    "visibility" -> Lucide.Eye
    "visibilityOff" -> Lucide.EyeOff
    "volumeDown" -> Lucide.Volume1
    "volumeMute" -> Lucide.Volume
    "volumeOff" -> Lucide.VolumeX
    "volumeUp" -> Lucide.Volume2
    "warning" -> Lucide.TriangleAlert
    else -> Lucide.CircleQuestionMark
}
