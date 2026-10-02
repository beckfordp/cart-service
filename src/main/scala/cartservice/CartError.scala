package cartservice

sealed trait CartError

case object CartNotFound extends CartError
