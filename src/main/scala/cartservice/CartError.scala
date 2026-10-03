package cartservice

sealed trait CartError

case object CartNotFound extends CartError
case object InvalidQuantity extends CartError
