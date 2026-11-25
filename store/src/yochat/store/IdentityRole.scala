package yochat.store

/** Local instance role. S2-A primary holds the user private key; companion must not. */
enum IdentityRole:
  case Primary, Companion

object IdentityRole:

  def fromCompanionFlag(companion: Boolean): IdentityRole =
    if companion then IdentityRole.Companion else IdentityRole.Primary
