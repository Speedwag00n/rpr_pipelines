enum Color{
        //text colors
        RED("\033[91m"), 
        BLACK("\033[30m"),
        BLUE("\033[34m"),
        GREEN("\033[92m"),
        YELLOW("\033[93m"),
        WHITE("\033[97m"),
        ORANGE("\033[38;5;208m"),
        DEFAULT("\033[39m"),
        
        //background colors
        BLACKB("\033[40m"),
        REDB("\033[41m"),
        GREENB("\033[42m"),
        YELLOWB("\033[43m"),
        BLUEB("\033[44m"),
        WHITEB("\033[107m"),
        DEFAULTB("\033[49m")
        
        private String value
        
        Color(String value) {
            this.value = value
        }
        String getValue() {
            value
        }
    }

void printColor(Object self, String content, String textColor, String backgroundColor){
    Color text = textColor
    Color background = backgroundColor
    self.ansiColor('xterm') {
        self.println(background.value + text.value + content + "\033[0m")
    }
}

void printError(Object self, String content){
    printColor(self, "[ERROR] " + content, "RED", "DEFAULTB")
}

void printDebug(Object self, String content){
    printColor(self, "[DEBUG CRITICAL] " + content, "RED", "DEFAULTB")
}

void printInfo(Object self, String content){
    printColor(self, "[INFO] " + content, "BLUE", "DEFAULTB")
}

void printWarning(Object self, String content){
    printColor(self, "[WARNING] " + content, "ORANGE", "DEFAULTB")
}

def call(String logType, String content){

    switch(logType){
        case 'ER':
            printError(this, content)
            break
        case 'IF':
            printInfo(this, content)
            break
        case 'WA':
            printWarning(this, content)
            break
        case 'DE':
            printDebug(this, content)
            break
        default:
            println(this, content)
            break
    }
}