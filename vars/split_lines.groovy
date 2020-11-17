// vars/split_lines.groovy
// vim: ts=4 sw=4 expandtab

def call(str) {
    if (str.size()) {
        return str.replaceAll('\r', '').split('\n')
    }
    return []
}
