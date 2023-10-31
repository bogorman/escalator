package escalator.common.util.console

object DebugPrint {

  def pCyan(str: String) = println(Console.CYAN + str + Console.RESET)

  def pGreen(str: String) = println(Console.GREEN + str + Console.RESET)

  def pYellow(str: String) = println(Console.YELLOW + str + Console.RESET)

  def pRed(str: String) = println(Console.RED + str + Console.RESET)

  // def println(message: String) = {
  //   Console.println("[" + Time.nowString + "] " + message)
  // }

}
